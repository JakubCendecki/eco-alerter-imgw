package ecoalerter.gui.panels;

import ecoalerter.gui.components.StationTable;
import ecoalerter.gui.dialogs.AddStationDialog;
import ecoalerter.gui.dialogs.IntervalConfigDialog;
import ecoalerter.model.Station;
import ecoalerter.model.StationType;
import ecoalerter.service.DataCollectionService;
import ecoalerter.service.NotificationService;
import ecoalerter.service.StationService;
import ecoalerter.util.AppLogger;
import org.apache.logging.log4j.Logger;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JToolBar;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.util.List;
import java.util.OptionalInt;

/**
 * Panel zarządzania stacjami pomiarowymi.
 *
 * Pozwala na dodawanie, usuwanie, aktywację/dezaktywację i zmianę interwału
 * stacji, oraz na manualne odświeżenie danych pojedynczej stacji poza
 * harmonogramem. Wszystkie operacje blokujące (zapis, API) wykonywane są
 * w SwingWorker, żeby nie zamrażać wątku EDT.
 *
 * Implementuje NotificationService.AppEventListener — automatycznie
 * aktualizuje kolumnę statusu w tabeli po zdarzeniach DATA_UPDATED/STATION_ERROR.
 */
public class StationManagerPanel extends JPanel implements NotificationService.AppEventListener {
	private static final long serialVersionUID = -4405033777447826344L;

	private static final Logger log = AppLogger.get(StationManagerPanel.class);

    private final StationService          stationService;
    private final DataCollectionService    dataCollectionService;
    private final NotificationService      notificationService;

    private final StationTable stationTable;
    private final JButton      removeButton;
    private final JButton      toggleActiveButton;
    private final JButton      changeIntervalButton;
    private final JButton      refreshNowButton;

    // -------------------------------------------------------------------------
    // Konstruktor
    // -------------------------------------------------------------------------

    public StationManagerPanel(StationService stationService,
                               DataCollectionService dataCollectionService,
                               NotificationService notificationService) {
        super(new BorderLayout());

        this.stationService        = stationService;
        this.dataCollectionService = dataCollectionService;
        this.notificationService   = notificationService;

        this.stationTable = new StationTable();
        stationTable.setActiveToggleListener(this::onActiveToggled);
        stationTable.getTable().getSelectionModel().addListSelectionListener(
                e -> updateButtonStates());

        JButton addButton = new JButton("Dodaj stację...");
        addButton.addActionListener(e -> onAddStation());

        this.removeButton = new JButton("Usuń");
        removeButton.addActionListener(e -> onRemoveStation());

        this.toggleActiveButton = new JButton("Aktywuj/Dezaktywuj");
        toggleActiveButton.addActionListener(e -> onToggleActiveSelected());

        this.changeIntervalButton = new JButton("Zmień interwał...");
        changeIntervalButton.addActionListener(e -> onChangeInterval());

        this.refreshNowButton = new JButton("Odśwież teraz");
        refreshNowButton.addActionListener(e -> onRefreshNow());

        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        toolBar.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        toolBar.add(addButton);
        toolBar.addSeparator();
        toolBar.add(removeButton);
        toolBar.add(toggleActiveButton);
        toolBar.add(changeIntervalButton);
        toolBar.addSeparator();
        toolBar.add(refreshNowButton);

        add(toolBar, BorderLayout.NORTH);
        add(stationTable, BorderLayout.CENTER);

        notificationService.addListener(this);
        updateButtonStates();
        reloadStations();
    }

    // -------------------------------------------------------------------------
    // Ładowanie danych
    // -------------------------------------------------------------------------

    /**
     * Przeładowuje listę stacji z repozytorium w tle i odświeża tabelę.
     * Bezpieczne do wywołania wielokrotnie — kolejne wywołania nie kolidują,
     * bo SwingWorker wykonuje się asynchronicznie i tylko aktualizuje model.
     */
    public void reloadStations() {
        new SwingWorker<List<Station>, Void>() {
            @Override
            protected List<Station> doInBackground() throws Exception {
                return stationService.getAllStations();
            }

            @Override
            protected void done() {
                try {
                    stationTable.setStations(get());
                } catch (Exception e) {
                    showError("Nie udało się wczytać listy stacji", e);
                }
            }
        }.execute();
    }

    // -------------------------------------------------------------------------
    // Akcje przycisków
    // -------------------------------------------------------------------------

    private void onAddStation() {
        AddStationDialog.showDialog(this).ifPresent(station ->
                new SwingWorker<Void, Void>() {
                    @Override
                    protected Void doInBackground() throws Exception {
                        stationService.addStation(station);
                        return null;
                    }

                    @Override
                    protected void done() {
                        try {
                            get();
                            reloadStations();
                        } catch (Exception e) {
                            showError("Nie udało się dodać stacji " + station.getId(), e);
                        }
                    }
                }.execute());
    }

    private void onRemoveStation() {
        Station selected = stationTable.getSelectedStation();
        if (selected == null) return;

        int confirm = JOptionPane.showConfirmDialog(this,
                "Usunąć stację " + selected.getDisplayLabel() + " wraz z historią danych?",
                "Potwierdzenie usunięcia", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return;

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                stationService.removeStation(selected.getId(), selected.getType());
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    notificationService.clearStatus(selected.getId());
                    reloadStations();
                } catch (Exception e) {
                    showError("Nie udało się usunąć stacji " + selected.getId(), e);
                }
            }
        }.execute();
    }

    private void onToggleActiveSelected() {
        Station selected = stationTable.getSelectedStation();
        if (selected == null) return;

        boolean newActive = !selected.isActive();
        setStationActive(selected, newActive);
    }

    private void onActiveToggled(Station station, boolean newActive) {
        setStationActive(station, newActive);
    }

    private void setStationActive(Station station, boolean active) {
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                if (active) {
                    stationService.activateStation(station.getId(), station.getType());
                } else {
                    stationService.deactivateStation(station.getId(), station.getType());
                }
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                } catch (Exception e) {
                    showError("Nie udało się zmienić stanu stacji " + station.getId(), e);
                } finally {
                    reloadStations();
                }
            }
        }.execute();
    }

    private void onChangeInterval() {
        Station selected = stationTable.getSelectedStation();
        if (selected == null) return;

        OptionalInt newInterval = IntervalConfigDialog.showDialog(this, selected);
        if (newInterval.isEmpty()) return;

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                stationService.updateInterval(
                        selected.getId(), selected.getType(), newInterval.getAsInt());
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    reloadStations();
                } catch (Exception e) {
                    showError("Nie udało się zmienić interwału stacji " + selected.getId(), e);
                }
            }
        }.execute();
    }

    private void onRefreshNow() {
        Station selected = stationTable.getSelectedStation();
        if (selected == null) return;

        refreshNowButton.setEnabled(false);

        new SwingWorker<Boolean, Void>() {
            private String errorMessage;

            @Override
            protected Boolean doInBackground() {
                try {
                    if (selected.getType() == StationType.METEO) {
                        dataCollectionService.fetchAndSaveMeteo(selected.getId());
                    } else {
                        dataCollectionService.fetchAndSaveHydro(selected.getId());
                    }
                    return true;
                } catch (Exception e) {
                    errorMessage = e.getMessage();
                    log.warn("Manualne odświeżenie nieudane dla {}: {}",
                            selected.getId(), e.getMessage());
                    return false;
                }
            }

            @Override
            protected void done() {
                refreshNowButton.setEnabled(true);
                try {
                    if (!get()) {
                        JOptionPane.showMessageDialog(StationManagerPanel.this,
                                "Błąd odświeżania: " + errorMessage,
                                "Odświeżanie nieudane", JOptionPane.WARNING_MESSAGE);
                    }
                } catch (Exception e) {
                    showError("Nieoczekiwany błąd odświeżania", e);
                }
            }
        }.execute();
    }

    // -------------------------------------------------------------------------
    // NotificationService.AppEventListener
    // -------------------------------------------------------------------------

    @Override
    public void onEvent(NotificationService.AppEvent event) {
        switch (event.getType()) {
            case DATA_UPDATED, STATION_ERROR -> {
                String stationId = extractStationId(event);
                if (stationId != null) {
                    stationTable.updateStatus(stationId, notificationService.getStatus(stationId));
                }
            }
            default -> { /* inne typy zdarzeń nie dotyczą tego panelu */ }
        }
    }

    private String extractStationId(NotificationService.AppEvent event) {
        Object payload = event.getPayload();
        if (payload instanceof Station s) return s.getId();
        if (payload instanceof NotificationService.StationStatus st) return st.getStationId();
        return null;
    }

    // -------------------------------------------------------------------------
    // Pomocnicze
    // -------------------------------------------------------------------------

    private void updateButtonStates() {
        boolean hasSelection = stationTable.getSelectedStation() != null;
        removeButton.setEnabled(hasSelection);
        toggleActiveButton.setEnabled(hasSelection);
        changeIntervalButton.setEnabled(hasSelection);
        refreshNowButton.setEnabled(hasSelection);
    }

    private void showError(String context, Exception e) {
        log.error("{}: {}", context, e.getMessage(), e);
        JOptionPane.showMessageDialog(this,
                context + ":\n" + e.getMessage(),
                "Błąd", JOptionPane.ERROR_MESSAGE);
    }

    /**
     * Wyrejestrowuje panel z NotificationService — wywołać przy zamykaniu zakładki/aplikacji.
     */
    public void dispose() {
        notificationService.removeListener(this);
    }
}
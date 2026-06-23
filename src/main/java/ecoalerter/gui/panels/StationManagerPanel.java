package ecoalerter.gui.panels;

import ecoalerter.api.ApiException;
import ecoalerter.gui.components.StationTable;
import ecoalerter.gui.dialogs.AddStationDialog;
import ecoalerter.model.Station;
import ecoalerter.model.StationType;
import ecoalerter.service.DataCollectionService;
import ecoalerter.service.NotificationService;
import ecoalerter.service.StationService;
import ecoalerter.util.AppLogger;
import org.apache.logging.log4j.Logger;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagLayout;
import java.util.List;

/**
 * Panel zarządzania stacjami pomiarowymi.
 *
 * Pozwala na dodawanie, usuwanie, edycję i zmianę aktywności stacji
 * (aktywność przełącza się checkboxem prosto w tabeli — nie ma do tego
 * osobnego przycisku, żeby nie duplikować tej samej akcji w dwóch miejscach),
 * oraz na manualne odświeżenie danych pojedynczej stacji poza harmonogramem.
 * Wszystkie operacje blokujące (zapis, API) wykonywane są w SwingWorker,
 * żeby nie zamrażać wątku EDT.
 *
 * Implementuje NotificationService.AppEventListener — automatycznie
 * aktualizuje kolumnę statusu w tabeli po zdarzeniach DATA_UPDATED/STATION_ERROR.
 */
public class StationManagerPanel extends JPanel implements NotificationService.AppEventListener {

    private static final Logger log = AppLogger.get(StationManagerPanel.class);

    private static final String CARD_TABLE = "table";
    private static final String CARD_EMPTY = "empty";

    /** Odstęp między przyciskami w poziomym rzędzie akcji (px). */
    private static final int BUTTON_GAP = 16;

    private final StationService          stationService;
    private final DataCollectionService    dataCollectionService;
    private final NotificationService      notificationService;

    private final StationTable stationTable;
    private final JPanel       centerPanel;
    private final JButton      removeButton;
    private final JButton      editButton;
    private final JButton      refreshButton;

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

        this.editButton = new JButton("Edytuj...");
        editButton.addActionListener(e -> onEditStation());

        this.refreshButton = new JButton("Odśwież");
        refreshButton.addActionListener(e -> onRefreshNow());

        JPanel toolBar = new JPanel(new FlowLayout(FlowLayout.LEFT, BUTTON_GAP, 6));
        toolBar.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        toolBar.add(addButton);
        toolBar.add(removeButton);
        toolBar.add(editButton);
        toolBar.add(refreshButton);

        add(toolBar, BorderLayout.NORTH);

        this.centerPanel = new JPanel(new CardLayout());
        centerPanel.add(stationTable, CARD_TABLE);
        centerPanel.add(buildEmptyStatePanel(), CARD_EMPTY);
        add(centerPanel, BorderLayout.CENTER);

        notificationService.addListener(this);
        updateButtonStates();
        showEmptyState(); // stan początkowy, do momentu pierwszego reloadStations()
        reloadStations();
    }

    /**
     * Budowa panelu wyświetlanego, gdy nie istnieje żadna dodana stacja —
     * zamiast pustej tabeli z nagłówkami, samo centrowane Microcopy.
     */
    private JPanel buildEmptyStatePanel() {
        JPanel panel = new JPanel(new GridBagLayout());

        JLabel label = new JLabel(
                "Brak dodanych stacji. Kliknij \u201eDodaj stację...\u201d, aby zacząć monitorowanie.");
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 14f));
        label.setForeground(Color.GRAY);

        panel.add(label); // GridBagLayout bez ograniczeń centruje pojedynczy komponent
        return panel;
    }

    private void showEmptyState() {
        ((CardLayout) centerPanel.getLayout()).show(centerPanel, CARD_EMPTY);
    }

    private void showTableState() {
        ((CardLayout) centerPanel.getLayout()).show(centerPanel, CARD_TABLE);
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
                    List<Station> stations = get();
                    stationTable.setStations(stations);
                    if (stations.isEmpty()) {
                        showEmptyState();
                    } else {
                        showTableState();
                    }
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
        AddStationDialog.showAddDialog(this).ifPresent(this::verifyAndAddStation);
    }

    /**
     * Weryfikuje w API IMGW, czy podana stacja faktycznie istnieje, zanim
     * zostanie zapisana w repozytorium. Zapobiega dodaniu literówki w ID,
     * która nigdy nie zwróciłaby żadnych danych pomiarowych.
     *
     * Zwykłe 404 (brak stacji) skutkuje komunikatem ostrzegawczym i przerwaniem
     * operacji. Błąd sieciowy/serwera skutkuje innym komunikatem, ale też
     * nie dodaje stacji — użytkownik może spróbować ponownie później.
     */
    private void verifyAndAddStation(Station station) {
        new SwingWorker<Boolean, Void>() {
            private String errorMessage;

            @Override
            protected Boolean doInBackground() {
                try {
                    return dataCollectionService.stationExists(station.getId(), station.getType());
                } catch (ApiException e) {
                    errorMessage = e.getMessage();
                    log.warn("Błąd weryfikacji stacji {} w API: {}", station.getId(), e.getMessage());
                    return null;
                }
            }

            @Override
            protected void done() {
                try {
                    Boolean exists = get();

                    if (exists == null) {
                        JOptionPane.showMessageDialog(StationManagerPanel.this,
                                "Nie udało się zweryfikować stacji w API IMGW:\n" + errorMessage +
                                "\n\nSprawdź połączenie z internetem i spróbuj ponownie.",
                                "Błąd weryfikacji", JOptionPane.ERROR_MESSAGE);
                        return;
                    }

                    if (!exists) {
                        JOptionPane.showMessageDialog(StationManagerPanel.this,
                                "Stacja o ID '" + station.getId() + "' [" + station.getType() +
                                "] nie istnieje w API IMGW.\nSprawdź poprawność identyfikatora i spróbuj ponownie.",
                                "Nieprawidłowe ID stacji", JOptionPane.WARNING_MESSAGE);
                        return;
                    }

                    saveNewStation(station);

                } catch (Exception e) {
                    showError("Nieoczekiwany błąd weryfikacji stacji " + station.getId(), e);
                }
            }
        }.execute();
    }

    private void saveNewStation(Station station) {
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
                    notificationService.notifyStationsChanged();
                } catch (Exception e) {
                    showError("Nie udało się dodać stacji " + station.getId(), e);
                }
            }
        }.execute();
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
                    notificationService.notifyStationsChanged();
                } catch (Exception e) {
                    showError("Nie udało się usunąć stacji " + selected.getId(), e);
                }
            }
        }.execute();
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
                    notificationService.notifyStationsChanged();
                }
            }
        }.execute();
    }

    private void onEditStation() {
        Station selected = stationTable.getSelectedStation();
        if (selected == null) return;

        AddStationDialog.showEditDialog(this, selected).ifPresent(updated ->
                new SwingWorker<Void, Void>() {
                    @Override
                    protected Void doInBackground() throws Exception {
                        stationService.editStation(updated);
                        return null;
                    }

                    @Override
                    protected void done() {
                        try {
                            get();
                            reloadStations();
                            notificationService.notifyStationsChanged();
                        } catch (Exception e) {
                            showError("Nie udało się zaktualizować stacji " + updated.getId(), e);
                        }
                    }
                }.execute());
    }

    private void onRefreshNow() {
        Station selected = stationTable.getSelectedStation();
        if (selected == null) return;

        refreshButton.setEnabled(false);

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
                refreshButton.setEnabled(true);
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
        editButton.setEnabled(hasSelection);
        refreshButton.setEnabled(hasSelection);
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
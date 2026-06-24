package ecoalerter.gui.panels;

import ecoalerter.api.ApiException;
import ecoalerter.gui.components.StationTable;
import ecoalerter.gui.dialogs.AddStationDialog;
import ecoalerter.model.Station;
import ecoalerter.model.StationType;
import ecoalerter.persistence.DuplicateStationException;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

/**
 * Panel zarządzania stacjami pomiarowymi.
 */
public class StationManagerPanel extends JPanel implements NotificationService.AppEventListener {
	private static final long serialVersionUID = -8429056874249512224L;

	private static final Logger log = AppLogger.get(StationManagerPanel.class);

    private static final String CARD_TABLE = "table";
    private static final String CARD_EMPTY = "empty";

    private static final int BUTTON_GAP = 16;

    private final StationService          stationService;
    private final DataCollectionService    dataCollectionService;
    private final NotificationService      notificationService;

    private final StationTable stationTable;
    private final JPanel       centerPanel;
    private final JButton      removeButton;
    private final JButton      editButton;
    private final JButton      refreshButton;
    private final JLabel       connectionBanner;

    /**
     * Stacje, które ostatnio raportowały błąd. Po przyjściu DATA_UPDATED dla
     * danej stacji jej ID jest usuwane — banner sam się ukryje, gdy zbiór
     * stanie się pusty. ConcurrentHashMap.newKeySet() bo modyfikujemy z EDT
     * (przez onEvent dostarczany przez NotificationService) i ewentualnie
     * innego wątku, jeśli notifikacje przyszłyby spoza EDT.
     */
    private final Set<String> stationsWithErrors = ConcurrentHashMap.newKeySet();

    /**
     * @param stationService        serwis stacji — dodawanie/usuwanie/edycja
     * @param dataCollectionService serwis danych — weryfikacja stacji w API,
     *                              manualne odświeżenie
     * @param notificationService   szyna zdarzeń — panel zarówno publikuje
     *                              ({@code STATIONS_CHANGED}) jak i subskrybuje
     *                              ({@code DATA_UPDATED}, {@code STATION_ERROR})
     */
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

        this.connectionBanner = new JLabel(" ");
        connectionBanner.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
        connectionBanner.setVisible(false);

        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.add(toolBar, BorderLayout.NORTH);
        topPanel.add(connectionBanner, BorderLayout.SOUTH);
        add(topPanel, BorderLayout.NORTH);

        this.centerPanel = new JPanel(new CardLayout());
        centerPanel.add(stationTable, CARD_TABLE);
        centerPanel.add(buildEmptyStatePanel(), CARD_EMPTY);
        add(centerPanel, BorderLayout.CENTER);

        notificationService.addListener(this);
        updateButtonStates();
        showEmptyState();
        reloadStations();
    }

    /**
     * Budowa panelu wyświetlanego, gdy nie istnieje żadna dodana stacja —
     * zamiast pustej tabeli z nagłówkami, samo centrowane Microcopy
     * kierujące do przycisku „Dodaj stację...".
     */
    private JPanel buildEmptyStatePanel() {
        JPanel panel = new JPanel(new GridBagLayout());

        JLabel label = new JLabel(
                "Brak dodanych stacji. Kliknij \u201eDodaj stację...\u201d, aby zacząć monitorowanie.");
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 14f));
        label.setForeground(Color.GRAY);

        panel.add(label);
        return panel;
    }

    /** Przełącza centerPanel na widok pustego stanu (Microcopy zamiast tabeli). */
    private void showEmptyState() {
        ((CardLayout) centerPanel.getLayout()).show(centerPanel, CARD_EMPTY);
    }

    /** Przełącza centerPanel z powrotem na tabelę stacji. */
    private void showTableState() {
        ((CardLayout) centerPanel.getLayout()).show(centerPanel, CARD_TABLE);
    }

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

    /** Obsługa kliknięcia „Dodaj stację..." — otwiera dialog i przekazuje wynik do weryfikacji. */
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

    /**
     * Faktyczny zapis nowej stacji po pomyślnej weryfikacji w API.
     *
     * Specjalnie obsługuje {@link DuplicateStationException} (rozpakowany
     * z {@link ExecutionException} rzucanego przez {@code SwingWorker.get()})
     * — pokazuje go jako Info, nie Error, bo duplikat to świadoma blokada
     * biznesowa, a nie awaria.
     */
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
                } catch (ExecutionException ex) {
                    // SwingWorker zawija wyjątek z doInBackground w ExecutionException —
                    // żeby rozróżnić „stacja już istnieje" (informacja) od prawdziwego
                    // błędu zapisu, sprawdzamy konkretny typ wyjątku przed pokazaniem
                    // okna jako ERROR_MESSAGE.
                    Throwable cause = ex.getCause();
                    if (cause instanceof DuplicateStationException dup) {
                        JOptionPane.showMessageDialog(StationManagerPanel.this,
                                dup.getMessage(),
                                "Stacja już dodana", JOptionPane.INFORMATION_MESSAGE);
                    } else {
                        showError("Nie udało się dodać stacji " + station.getId(),
                                cause instanceof Exception e2 ? e2 : ex);
                    }
                } catch (Exception e) {
                    showError("Nie udało się dodać stacji " + station.getId(), e);
                }
            }
        }.execute();
    }

    /**
     * Obsługa „Usuń". Pyta o potwierdzenie i kasuje stację razem z historią
     * danych (kasowanie plików meteo/hydro jest w
     * {@link ecoalerter.persistence.file.FileRepository#deleteStation}).
     */
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

    /**
     * Callback z checkboxa „Aktywna" w wierszu tabeli — przerzuca decyzję
     * do {@link #setStationActive(Station, boolean)}.
     */
    private void onActiveToggled(Station station, boolean newActive) {
        setStationActive(station, newActive);
    }

    /**
     * Aktywuje lub dezaktywuje stację w tle (SwingWorker). Niezależnie od
     * sukcesu odświeża tabelę i powiadamia inne panele o zmianie — żeby
     * GUI pozostało spójne nawet po częściowej awarii.
     */
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

    /**
     * Obsługa „Edytuj...". Otwiera dialog edycji wstępnie wypełniony wartościami
     * wybranej stacji; po zatwierdzeniu zapisuje przez {@link StationService#editStation}.
     */
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

    /**
     * Obsługa „Odśwież". Wykonuje pojedyncze pobranie z API poza harmonogramem
     * dla aktualnie wybranej stacji. Błąd jest pokazywany jako warning
     * (np. „brak danych z API") — manualne odświeżenie nie powinno wieszać
     * UI ani wymuszać akcji na użytkowniku.
     */
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

    /**
     * Reaguje na zdarzenia z {@link NotificationService}: aktualizuje kolumnę
     * statusu w tabeli, śledzi stacje z błędami w {@link #stationsWithErrors}
     * i pokazuje/ukrywa banner braku połączenia.
     *
     * Dodatkowo obsługuje {@code STATIONS_CHANGED} — przeładowuje tabelę
     * gdy ktoś z zewnątrz (np. SettingsPanel po „Wyczyść wszystkie dane")
     * zmienił listę stacji.
     */
    @Override
    public void onEvent(NotificationService.AppEvent event) {
        switch (event.getType()) {
            case DATA_UPDATED, STATION_ERROR -> {
                String stationId = extractStationId(event);
                if (stationId != null) {
                    stationTable.updateStatus(stationId, notificationService.getStatus(stationId));
                    trackStationStatus(stationId, event);
                }
                updateConnectionBanner();
            }
            case STATIONS_CHANGED -> {
                // Lista stacji została zmieniona przez zewnętrzną akcję
                // (np. clear-all w Ustawieniach) — czyścimy stan błędów,
                // bo nieaktualne ID nie powinny utrzymywać czerwonego bannera,
                // i przeładowujemy tabelę.
                stationsWithErrors.clear();
                updateConnectionBanner();
                reloadStations();
            }
            default -> { /* inne typy zdarzeń nie dotyczą tego panelu */ }
        }
    }

    /**
     * Aktualizuje zbiór stacji z błędami na podstawie typu zdarzenia.
     * STATION_ERROR — dodaj; DATA_UPDATED — usuń (znaczy, że stacja znów działa).
     */
    private void trackStationStatus(String stationId, NotificationService.AppEvent event) {
        switch (event.getType()) {
            case STATION_ERROR -> stationsWithErrors.add(stationId);
            case DATA_UPDATED  -> stationsWithErrors.remove(stationId);
            default -> { /* nie dotyczy */ }
        }
    }

    /**
     * Pokazuje czerwony banner nad tabelą gdy przynajmniej jedna stacja ma
     * błąd komunikacji. Pomaga w typowym scenariuszu „start offline" —
     * status w kolumnie tabeli jest łatwy do przeoczenia, banner u góry
     * jednoznacznie sygnalizuje brak połączenia z IMGW i co z tym zrobić.
     */
    private void updateConnectionBanner() {
        if (stationsWithErrors.isEmpty()) {
            connectionBanner.setVisible(false);
            return;
        }

        int count = stationsWithErrors.size();
        String text = count == 1
                ? "Nie udało się pobrać danych dla 1 stacji. Sprawdź połączenie z internetem " +
                  "i kliknij „Odśwież\", gdy wrócisz online."
                : "Nie udało się pobrać danych dla " + count + " stacji. Sprawdź połączenie " +
                  "z internetem i kliknij „Odśwież\", gdy wrócisz online.";

        connectionBanner.setText(text);
        connectionBanner.setOpaque(true);
        connectionBanner.setBackground(new Color(0xFFEBEE)); // jasnoczerwone tło (Material Red 50)
        connectionBanner.setForeground(new Color(0xB71C1C)); // ciemnoczerwony tekst (Material Red 900)
        connectionBanner.setVisible(true);
    }

    /**
     * Wyciąga {@code stationId} ze zdarzenia. Obsługuje dwa formaty payloadu
     * (Station lub StationStatus); dla pozostałych zwraca null.
     */
    private String extractStationId(NotificationService.AppEvent event) {
        Object payload = event.getPayload();
        if (payload instanceof Station s) return s.getId();
        if (payload instanceof NotificationService.StationStatus st) return st.getStationId();
        return null;
    }

    // -------------------------------------------------------------------------
    // Pomocnicze
    // -------------------------------------------------------------------------

    /**
     * Włącza/wyłącza przyciski akcji zależnie od tego, czy w tabeli jest zaznaczony wiersz.
     * Niektóre operacje (Usuń, Edytuj, Odśwież) wymagają wybranej stacji — bez selekcji nie powinny być aktywne.
     */
    private void updateButtonStates() {
        boolean hasSelection = stationTable.getSelectedStation() != null;
        removeButton.setEnabled(hasSelection);
        editButton.setEnabled(hasSelection);
        refreshButton.setEnabled(hasSelection);
    }

    /**
     * Loguje błąd i pokazuje go w dialogu jako ERROR_MESSAGE.
     * Format komunikatu: „{context}:\n{e.getMessage()}".
     */
    private void showError(String context, Exception e) {
        log.error("{}: {}", context, e.getMessage(), e);
        JOptionPane.showMessageDialog(this,
                context + ":\n" + e.getMessage(),
                "Błąd", JOptionPane.ERROR_MESSAGE);
    }

    /**
     * Wyrejestrowuje panel z {@link NotificationService}.
     * Wywołać przy zamykaniu zakładki lub aplikacji, żeby zapobiec wyciekowi
     * referencji do listenera.
     */
    public void dispose() {
        notificationService.removeListener(this);
    }
}
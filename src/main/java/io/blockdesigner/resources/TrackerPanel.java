package io.blockdesigner.resources;

import io.blockdesigner.core.model.BlockState;
import io.blockdesigner.plugin.PanelContext;
import io.blockdesigner.plugin.PluginContext;
import io.blockdesigner.plugin.PluginPanel;
import io.blockdesigner.plugin.SceneEvent;
import io.blockdesigner.plugin.Subscription;
import io.blockdesigner.plugin.ui.ActionBar;
import io.blockdesigner.plugin.ui.Controls;
import io.blockdesigner.plugin.ui.EmptyState;
import io.blockdesigner.plugin.ui.Form;
import io.blockdesigner.plugin.ui.Icon;
import io.blockdesigner.plugin.ui.ItemList;
import io.blockdesigner.plugin.ui.PanelScaffold;
import io.blockdesigner.plugin.ui.Section;
import io.blockdesigner.plugin.ui.Theme;
import io.blockdesigner.plugin.ui.Tone;
import javafx.animation.PauseTransition;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Materials page: where to count, then every item the build needs (filter, Hide done, sort), each with how many
 * are placed in the game (when linked to a BlockCompanion build), how many you've gathered (typed in, or ticked done),
 * how many the game's linked chests hold, and what's left, with a bar that fills red to green. At the bottom: the
 * overall progress and the game line, then Copy list, Save CSV and Reset. It recounts as the build changes, while it's
 * on screen.
 */
final class TrackerPanel implements PluginPanel {
    private enum Sort {
        LEFT("Most left"), NEEDED("Most needed"), NAME("Name");

        final String label;

        Sort(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final TrackerSession session;
    private final Tracker tracker;
    private final Prefs prefs;
    private final TrackerSession.Listener listener = new TrackerSession.Listener() {
        @Override
        public void countChanged(boolean recount) {
            if (recount) changed();
            else if (list != null) show();
        }

        @Override
        public void tick() {
            if (list != null) showGame();
        }
    };
    private final List<Subscription> subscriptions = new ArrayList<>();
    private final Map<String, Image> icons = new HashMap<>();
    private final ObservableList<Tally.Need> rows = FXCollections.observableArrayList();
    private PanelContext panel;
    private PluginContext ctx;
    private boolean stale = true;
    private PauseTransition debounce;
    private TextField search;
    private ToggleButton hideDone;
    private ComboBox<Sort> sort;
    private GradientBar progress;
    private Label progressText;
    private Label gameLine;
    private HBox gameRow;
    private ItemList<Tally.Need> list;
    private EmptyState empty;

    TrackerPanel(TrackerSession session) {
        this.session = session;
        this.tracker = session.tracker;
        this.prefs = session.prefs;
    }

    @Override
    public String id() {
        return "materials";
    }

    @Override
    public String title() {
        return "Materials";
    }

    @Override
    public String icon() {
        // A box, open at the top.
        return "M2 5 L8 2 L14 5 L14 11 L8 14 L2 11 Z M2 5 L8 8 L14 5 M8 8 L8 14";
    }

    @Override
    public Node create(PanelContext context) {
        this.panel = context;
        this.ctx = context.plugin();
        debounce = new PauseTransition(Duration.millis(250));
        debounce.setOnFinished(e -> refresh());
        // Anything that can change the count; events come at most once per frame.
        subscriptions.add(ctx.on(SceneEvent.BlocksChanged.class, e -> changed()));
        subscriptions.add(ctx.on(SceneEvent.LayersChanged.class, e -> changed()));
        subscriptions.add(ctx.on(SceneEvent.ActiveLayerChanged.class, e -> changed()));
        subscriptions.add(ctx.on(SceneEvent.SelectionChanged.class, e -> {
            if (tracker.scope == Tally.Scope.SELECTION) changed();
        }));
        context.onShown(() -> {
            if (stale) refresh();
        });

        // Where to count.
        ComboBox<Tally.Scope> scope = new ComboBox<>(FXCollections.observableArrayList(Tally.Scope.values()));
        scope.setValue(tracker.scope);
        scope.setTooltip(new Tooltip("Where to count what the build needs"));
        scope.valueProperty().addListener((o, a, b) -> {
            tracker.scope = b;
            prefs.scope = b.name();
            prefs.save();
            refresh();
        });
        Form countForm = new Form();
        countForm.row("Count in", scope);
        Section count = new Section("Count", countForm)
                .actions(Controls.iconButton(Icon.REFRESH, "Count again, and look at the game's progress and chests again", session::recountAll));

        // The items.
        search = Controls.search("Filter items");
        search.textProperty().addListener((o, a, b) -> show());
        hideDone = Controls.toggle("Hide done", "Hide the items you have enough of");
        hideDone.setSelected(prefs.hideDone);
        hideDone.selectedProperty().addListener((o, a, b) -> {
            prefs.hideDone = b;
            prefs.save();
            show();
        });
        sort = new ComboBox<>(FXCollections.observableArrayList(Sort.values()));
        sort.setValue(Prefs.constant(Sort.class, prefs.sort, Sort.LEFT));
        sort.setPrefWidth(130);
        sort.setMinWidth(0);
        sort.setTooltip(new Tooltip("Sort the items"));
        sort.valueProperty().addListener((o, a, b) -> {
            prefs.sort = b.name();
            prefs.save();
            show();
        });
        HBox filters = new HBox(Theme.SM, hideDone, Controls.spacer(), sort);
        filters.setAlignment(Pos.CENTER_LEFT);
        empty = new EmptyState(Icon.INFO, "Nothing to count.");
        list = new ItemList<Tally.Need>().empty(empty);
        list.setItems(rows);
        list.setCellFactory(v -> new Row());
        Section items = new Section("Items", search, filters).grow(list);

        // How far along, at the bottom.
        progress = new GradientBar(8);
        progress.setMaxWidth(Double.MAX_VALUE);
        progressText = Controls.caption("");
        progressText.setWrapText(true);
        gameLine = Controls.caption("");
        gameLine.setTooltip(new Tooltip("The build in the game this project is linked to: change it on the BlockCompanion page"));
        HBox.setHgrow(gameLine, Priority.ALWAYS);
        Hyperlink toGame = Controls.link("BlockCompanion page", () -> ctx.showPanel(TrackerSession.GAME_PAGE));
        gameRow = new HBox(Theme.SM, gameLine, toGame);
        gameRow.setAlignment(Pos.CENTER_LEFT);
        VBox status = new VBox(Theme.XS, progress, progressText, gameRow);

        Button copy = Controls.button("Copy list", "Copy what is left to gather as text", session::copyList);
        Button csv = Controls.button("Save CSV…", "Save needed, placed (when linked to the game), gathered and left for every item as a spreadsheet",
                this::saveCsv);
        Button reset = Controls.button("Reset…", "Forget what you have gathered for this project", this::reset);

        PanelScaffold page = new PanelScaffold()
                .add(count)
                .grow(items)
                .footer(status, new ActionBar(copy, csv, Controls.spacer(), reset));
        session.addListener(listener);
        tracker.setChests(session.links.chests());
        refresh();
        return page;
    }

    @Override
    public void dispose() {
        subscriptions.forEach(Subscription::cancel);
        subscriptions.clear();
        session.removeListener(listener);
        if (debounce != null) debounce.stop();
        list = null;
    }

    private void reset() {
        if (!ctx.ui().confirm("Reset gathered", "Forget what you have gathered for " + tracker.projectName()
                + "? Blocks placed in the game still count.", "Reset", true)) return;
        tracker.resetGathered();
        show();
    }

    /** The line about the build in the game, when linked. */
    private void showGame() {
        GameProgress p = tracker.linked();
        String text = p == null ? "" : p.status(java.time.Instant.now());
        gameLine.setText(text);
        Controls.show(gameRow, !text.isEmpty());
    }

    private void changed() {
        stale = true;
        if (panel != null && panel.isShowing()) debounce.playFromStart();
    }

    private void refresh() {
        stale = false;
        tracker.recount();
        show();
    }

    /** Filters, sorts and shows the counted items, and updates the totals. */
    private void show() {
        String q = search.getText() == null ? "" : search.getText().strip().toLowerCase(Locale.ROOT);
        List<Tally.Need> shown = new ArrayList<>();
        long needed = 0, have = 0;
        int done = 0;
        boolean game = tracker.linked() != null;
        for (Tally.Need n : tracker.needs()) {
            long left = tracker.left(n);
            needed += n.count();
            have += n.count() - left;   // placed + gathered, capped at what is needed
            if (left == 0) done++;
            if (hideDone.isSelected() && left == 0) continue;
            if (!q.isEmpty() && !n.item().contains(q) && !tracker.name(n.item()).toLowerCase(Locale.ROOT).contains(q)) continue;
            shown.add(n);
        }
        Comparator<Tally.Need> order = switch (sort.getValue()) {
            case LEFT -> Comparator.comparingLong((Tally.Need n) -> -tracker.left(n)).thenComparing(n -> -n.count());
            case NEEDED -> Comparator.comparingLong((Tally.Need n) -> -n.count());
            case NAME -> Comparator.comparing(n -> tracker.name(n.item()).toLowerCase(Locale.ROOT));
        };
        shown.sort(order.thenComparing(Tally.Need::item));
        rows.setAll(shown);
        int kinds = tracker.needs().size();
        boolean noSelection = tracker.scope == Tally.Scope.SELECTION && ctx.selection().isEmpty();
        empty.hint(kinds > 0 ? "No item matches the filter." : noSelection ? "Select some blocks to count them." : "Build something to count it.");
        progress.setValue(needed == 0 ? 0 : (double) have / needed);
        progressText.setText(kinds == 0
                ? (noSelection ? "Select some blocks to count them" : "Nothing to count")
                : String.format("%,d of %,d items %s (%d%%) · %d of %d kinds done", have, needed,
                game ? (tracker.hasChests() ? "placed, gathered or in chests" : "placed or gathered") : tracker.hasChests() ? "gathered or in chests" : "gathered",
                needed == 0 ? 0 : Math.round(100.0 * have / needed), done, kinds));
        int left = kinds - done;
        panel.setBadge(left == 0 ? null : Integer.toString(left));
        showGame();
        // Minecraft's assets (the icons) can load after the first count: redraw the rows shortly, for up to a minute.
        if (!shown.isEmpty()) waitForIcons(shown.getFirst());
    }

    /** How often the rows were redrawn waiting for block icons. */
    private int iconRetries;
    private boolean waitingForIcons;

    private void waitForIcons(Tally.Need sample) {
        if (waitingForIcons || sample.icon() == null || icon(sample) != null || iconRetries >= 30) return;
        waitingForIcons = true;
        PauseTransition retry = new PauseTransition(Duration.seconds(2));
        retry.setOnFinished(e -> {
            waitingForIcons = false;
            iconRetries++;
            if (list == null) return;
            list.refresh();
            waitForIcons(sample);
        });
        retry.play();
    }

    private void saveCsv() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Save materials");
        fc.setInitialFileName("materials.csv");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV spreadsheet", "*.csv"));
        var file = fc.showSaveDialog(ctx.ui().owner());
        if (file == null) return;
        try {
            Files.writeString(file.toPath(), tracker.csv(), StandardCharsets.UTF_8);
            ctx.toast("Saved " + file.getName());
        } catch (IOException e) {
            ctx.toast("Could not save: " + e.getMessage());
        }
    }

    private Image icon(Tally.Need n) {
        BlockState st = n.icon();
        if (st == null) return null;
        Image cached = icons.get(st.toString());
        if (cached != null) return cached;
        // Not cached while missing: Minecraft's assets can load after the page was first drawn.
        Image i = ctx.blockIcon(st).orElse(null);
        if (i != null) icons.put(st.toString(), i);
        return i;
    }

    /** One item: icon, name, what's left and needed (as stacks), a box for how many you have, and a done button. */
    private final class Row extends ListCell<Tally.Need> {
        private final ImageView iv = new ImageView();
        private final Label name = new Label(), meta = new Label();
        private final GradientBar bar = new GradientBar(5);
        private final TextField have = new TextField();
        private final Button done = Controls.iconButton(Icon.CHECK, "Gathered all of it", null);
        private final HBox root;
        private final Tooltip rowTip = new Tooltip();
        private final Tooltip haveTip = new Tooltip();
        private Tally.Need bound;

        Row() {
            getStyleClass().add("bd-list-cell");
            iv.setFitWidth(24);
            iv.setFitHeight(24);
            iv.setSmooth(false);
            iv.setPreserveRatio(true);
            name.getStyleClass().add("bd-row-title");
            name.setMinWidth(0);
            name.setTextOverrun(OverrunStyle.ELLIPSIS);
            meta.getStyleClass().add("bd-row-meta");
            meta.setMinWidth(0);
            bar.setMaxWidth(Double.MAX_VALUE);
            VBox text = new VBox(2, name, meta, bar);
            text.setMinWidth(0);
            text.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(text, Priority.ALWAYS);
            have.getStyleClass().add("bd-inline-field");
            have.setPrefColumnCount(4);
            have.setMinWidth(56);
            have.setPrefWidth(56);
            have.setPromptText("0");
            have.setTooltip(haveTip);
            have.setAccessibleText("How many you have");
            have.setOnAction(e -> commit());
            have.focusedProperty().addListener((o, a, b) -> {
                if (!b) commit();
            });
            done.setOnAction(e -> {
                if (bound == null) return;
                // All of what isn't placed in the game yet (all of it when not linked).
                tracker.setGathered(bound.item(), tracker.left(bound) == 0 ? 0
                        : Tracker.rest(bound.count(), tracker.placed(bound.item()), tracker.chests(bound.item())));
                show();
            });
            root = new HBox(Theme.SM, iv, text, have, done);
            Tooltip.install(text, rowTip);
            root.setAlignment(Pos.CENTER_LEFT);
            root.setMinWidth(0);
            // Fit the list's width (no sideways scrolling): the name shortens instead.
            setPrefWidth(0);
        }

        private void commit() {
            if (bound == null) return;
            var n = Tracker.parse(have.getText(), bound.item());
            if (n.isEmpty()) {
                have.setText(format(tracker.gathered.get(bound.item())));
                return;
            }
            if (n.get() == tracker.gathered.get(bound.item())) return;
            tracker.setGathered(bound.item(), n.get());
            show();
        }

        @Override
        protected void updateItem(Tally.Need n, boolean empty) {
            super.updateItem(n, empty);
            bound = empty ? null : n;
            setText(null);
            if (n == null || empty) {
                setGraphic(null);
                return;
            }
            iv.setImage(icon(n));
            name.setText(tracker.name(n.item()));
            long got = tracker.gathered.get(n.item()), l = tracker.left(n);
            String need = Items.stacks(n.count(), n.item());
            boolean game = tracker.linked() != null;
            long placed = tracker.placed(n.item());
            boolean allPlaced = game && placed >= n.count();
            String metaText, tip;
            if (!game) {
                metaText = l == 0 ? "Done · " + need : l == n.count() ? "Need " + need : String.format("%,d left · need %s", l, need);
                tip = String.format("Needed: %,d · gathered: %,d · left: %,d", n.count(), got, l);
                haveTip.setText("How many you have: a number, stacks (10s), shulker boxes (2sh), or a sum (1sh + 3s + 12)");
            } else {
                metaText = allPlaced ? "All placed · need " + need
                        : l == 0 ? String.format("Done · %,d placed · need %s", placed, need)
                        : String.format("%,d left · %,d placed · need %s", l, placed, need);
                tip = String.format("Needed: %,d · placed in the game: %,d · gathered (in hand, not placed yet): %,d · left: %,d%n"
                        + "Left = needed − placed − gathered. Placed blocks count by themselves: type only what you have and haven't placed yet.",
                        n.count(), placed, got, l);
                haveTip.setText("How many you have and haven't placed yet: a number, stacks (10s), shulker boxes (2sh), or a sum (1sh + 3s + 12)");
            }
            long inChests = tracker.chests(n.item());
            if (inChests > 0) {
                metaText += String.format(" · %,d in chests", inChests);
                tip += String.format("%nIn your linked chests in the game: %,d (counts as gathered).", inChests);
            }
            meta.setText(metaText);
            rowTip.setText(tip);
            Tone.apply(meta, l == 0 ? Tone.SUCCESS : Tone.NEUTRAL);
            bar.setValue(n.count() == 0 ? 1 : (n.count() - l) / (double) n.count());
            have.setText(got == 0 ? "" : format(got));
            done.setDisable(allPlaced);
            boolean undo = l == 0 && !allPlaced;
            done.setGraphic((undo ? Icon.UNDO : Icon.CHECK).node(16));
            String doneTip = allPlaced ? "All placed in the game" : undo ? "Not gathered yet after all" : "Gathered all of it";
            done.getTooltip().setText(doneTip);
            done.setAccessibleText(doneTip);
            setGraphic(root);
        }

        private static String format(long n) {
            return Long.toString(n);
        }
    }
}

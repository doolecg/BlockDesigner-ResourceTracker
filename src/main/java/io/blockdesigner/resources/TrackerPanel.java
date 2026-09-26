package io.blockdesigner.resources;

import io.blockdesigner.core.model.BlockState;
import io.blockdesigner.plugin.PanelContext;
import io.blockdesigner.plugin.PluginContext;
import io.blockdesigner.plugin.PluginPanel;
import io.blockdesigner.plugin.SceneEvent;
import io.blockdesigner.plugin.Subscription;
import javafx.animation.PauseTransition;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
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
 * The Resource Tracker panel: every item the build needs, how many you've gathered (typed in, or ticked done) and
 * what's left, with an overall progress bar. It recounts as the build changes, while it's on screen.
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

    private final Tracker tracker;
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
    private ProgressBar progress;
    private Label progressText;
    private ListView<Tally.Need> list;

    TrackerPanel(Tracker tracker) {
        this.tracker = tracker;
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
        subscriptions.add(ctx.on(SceneEvent.ProjectOpened.class, e -> changed()));
        subscriptions.add(ctx.on(SceneEvent.SelectionChanged.class, e -> {
            if (tracker.scope == Tally.Scope.SELECTION) changed();
        }));
        context.onShown(() -> {
            if (stale) refresh();
        });

        ComboBox<Tally.Scope> scope = new ComboBox<>(FXCollections.observableArrayList(Tally.Scope.values()));
        scope.setValue(tracker.scope);
        scope.setTooltip(new Tooltip("Where to count what the build needs"));
        scope.valueProperty().addListener((o, a, b) -> {
            tracker.scope = b;
            refresh();
        });
        scope.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(scope, Priority.ALWAYS);
        CheckBox mobs = new CheckBox("Mobs");
        mobs.setSelected(tracker.mobs);
        mobs.setTooltip(new Tooltip("Count mobs as their spawn eggs (paintings, frames, armor stands, boats and minecarts always count)"));
        mobs.selectedProperty().addListener((o, a, b) -> {
            tracker.mobs = b;
            refresh();
        });
        HBox top = new HBox(8, scope, mobs);
        top.setAlignment(Pos.CENTER_LEFT);

        search = new TextField();
        search.setPromptText("Filter…");
        search.textProperty().addListener((o, a, b) -> show());
        HBox.setHgrow(search, Priority.ALWAYS);
        hideDone = new ToggleButton("Hide done");
        hideDone.setMinWidth(Region.USE_PREF_SIZE);
        hideDone.setTooltip(new Tooltip("Hide the items you have enough of"));
        hideDone.selectedProperty().addListener((o, a, b) -> show());
        sort = new ComboBox<>(FXCollections.observableArrayList(Sort.values()));
        sort.setValue(Sort.LEFT);
        sort.setMinWidth(Region.USE_PREF_SIZE);
        search.setMinWidth(60);
        sort.valueProperty().addListener((o, a, b) -> show());
        HBox filters = new HBox(6, search, hideDone, sort);
        filters.setAlignment(Pos.CENTER_LEFT);

        progress = new ProgressBar(0);
        progress.setMaxWidth(Double.MAX_VALUE);
        progressText = new Label();
        muted(progressText);

        list = new ListView<>(rows);
        list.setCellFactory(v -> new Row());
        list.setPlaceholder(new Label("Nothing to gather here yet."));
        VBox.setVgrow(list, Priority.ALWAYS);

        Button copy = new Button("Copy list");
        copy.setTooltip(new Tooltip("Copy what is left to gather as text"));
        copy.setOnAction(e -> copyList());
        Button csv = new Button("Save CSV…");
        csv.setTooltip(new Tooltip("Save needed, gathered and left for every item as a spreadsheet"));
        csv.setOnAction(e -> saveCsv());
        Button reset = new Button("Reset");
        reset.setTooltip(new Tooltip("Forget what you have gathered for this project"));
        reset.setOnAction(e -> {
            tracker.resetGathered();
            show();
        });
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox buttons = new HBox(6, copy, csv, grow, reset);

        VBox root = new VBox(8, top, filters, progress, progressText, list, buttons);
        root.setPadding(new Insets(10));
        refresh();
        return root;
    }

    @Override
    public void dispose() {
        subscriptions.forEach(Subscription::cancel);
        subscriptions.clear();
        if (debounce != null) debounce.stop();
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
        for (Tally.Need n : tracker.needs()) {
            long got = Math.min(n.count(), tracker.gathered.get(n.item()));
            needed += n.count();
            have += got;
            if (got >= n.count()) done++;
            if (hideDone.isSelected() && tracker.left(n) == 0) continue;
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
        progress.setProgress(needed == 0 ? 0 : (double) have / needed);
        progressText.setText(kinds == 0
                ? (tracker.scope == Tally.Scope.SELECTION && ctx.selection().isEmpty() ? "Select some blocks to count them" : "Nothing to count")
                : String.format("%,d of %,d items gathered (%d%%) · %d of %d kinds done", have, needed,
                needed == 0 ? 0 : Math.round(100.0 * have / needed), done, kinds));
        int left = kinds - done;
        panel.setBadge(left == 0 ? null : Integer.toString(left));
    }

    private void copyList() {
        ClipboardContent c = new ClipboardContent();
        c.putString(tracker.text());
        Clipboard.getSystemClipboard().setContent(c);
        ctx.toast("Materials list copied");
    }

    private void saveCsv() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Save materials");
        fc.setInitialFileName("materials.csv");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV spreadsheet", "*.csv"));
        var window = list.getScene() == null ? null : list.getScene().getWindow();
        var file = fc.showSaveDialog(window);
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
        return icons.computeIfAbsent(st.toString(), k -> ctx.blockIcon(st).orElse(null));
    }

    private static void muted(Label l) {
        l.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 11px;");
    }

    /** One item: icon, name, what's left and needed (as stacks), a box for how many you have, and a done button. */
    private final class Row extends ListCell<Tally.Need> {
        private final ImageView iv = new ImageView();
        private final Label name = new Label(), meta = new Label();
        private final TextField have = new TextField();
        private final Button done = new Button("✓");
        private final HBox root;
        private Tally.Need bound;

        Row() {
            iv.setFitWidth(24);
            iv.setFitHeight(24);
            iv.setSmooth(false);
            name.setStyle("-fx-font-weight: bold;");
            name.setMinWidth(0);
            meta.setMinWidth(0);
            muted(meta);
            VBox text = new VBox(0, name, meta);
            text.setMinWidth(0);
            text.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(text, Priority.ALWAYS);
            have.setPrefColumnCount(4);
            // A visible box in any theme (list cells otherwise flatten text fields).
            have.setStyle("-fx-background-color: -color-bg-default; -fx-border-color: -color-border-default; -fx-border-radius: 4;"
                    + " -fx-background-radius: 4; -fx-alignment: center-right;");
            have.setMinWidth(Region.USE_PREF_SIZE);
            done.setMinWidth(Region.USE_PREF_SIZE);
            iv.setPreserveRatio(true);
            have.setPromptText("0");
            have.setTooltip(new Tooltip("How many you have: a number, stacks (10s), shulker boxes (2sh), or a sum (1sh + 3s + 12)"));
            have.setOnAction(e -> commit());
            have.focusedProperty().addListener((o, a, b) -> {
                if (!b) commit();
            });
            done.setTooltip(new Tooltip("Gathered all of it"));
            done.setOnAction(e -> {
                if (bound == null) return;
                tracker.setGathered(bound.item(), tracker.left(bound) == 0 ? 0 : bound.count());
                show();
            });
            root = new HBox(8, iv, text, have, done);
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
            if (n == null || empty) {
                setGraphic(null);
                return;
            }
            iv.setImage(icon(n));
            name.setText(tracker.name(n.item()));
            long got = tracker.gathered.get(n.item()), l = tracker.left(n);
            String need = Items.stacks(n.count(), n.item());
            meta.setText(l == 0 ? "Done · " + need : l == n.count() ? "Need " + need : String.format("%,d left · need %s", l, need));
            meta.setStyle(l == 0 ? "-fx-text-fill: -color-success-fg; -fx-font-size: 11px;" : "-fx-text-fill: -color-fg-muted; -fx-font-size: 11px;");
            have.setText(got == 0 ? "" : format(got));
            done.setText(l == 0 ? "↺" : "✓");
            done.getTooltip().setText(l == 0 ? "Not gathered yet after all" : "Gathered all of it");
            setGraphic(root);
        }

        private static String format(long n) {
            return Long.toString(n);
        }
    }
}

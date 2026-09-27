package io.blockdesigner.resources;

import com.fasterxml.jackson.databind.JsonNode;
import io.blockdesigner.plugin.PluginContext;
import io.blockdesigner.plugin.ui.ActionBar;
import io.blockdesigner.plugin.ui.Banner;
import io.blockdesigner.plugin.ui.Controls;
import io.blockdesigner.plugin.ui.EmptyState;
import io.blockdesigner.plugin.ui.Form;
import io.blockdesigner.plugin.ui.Icon;
import io.blockdesigner.plugin.ui.ItemList;
import io.blockdesigner.plugin.ui.PanelScaffold;
import io.blockdesigner.plugin.ui.Section;
import io.blockdesigner.plugin.ui.StatusBadge;
import io.blockdesigner.plugin.ui.Theme;
import io.blockdesigner.plugin.ui.Tone;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The BlockCompanion page: every BlockCompanion game and server on this computer, each with a box for whether projects
 * go there and how it stands; Send to game and Live (send after every change); the build in the game this project is
 * linked to (Game progress) and the linked chests; installing the mod. At the bottom: how the link stands and what the
 * last install did.
 */
final class GameLinkPane {
    private final PluginContext ctx;
    private final TrackerSession session;
    private final GameLinks links;
    private final Tracker tracker;
    private final Runnable linkChanged;
    private final ObservableList<GameInstance> items = FXCollections.observableArrayList();
    private ItemList<GameInstance> list;
    private StatusBadge state;
    private Label stateDetail, chestsText, gameStatus;
    private Banner banner;
    private ToggleButton live;
    private Button textures;
    private ComboBox<Choice> gameLink;
    private boolean updatingChoices;
    private final GameLinks.Listener listener = this::update;

    /** An entry of the game progress picker. */
    private record Choice(Gathered.Link link, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    /** {@code linkChanged}: the project was linked to another build in the game, so the Materials page counts again. */
    GameLinkPane(TrackerSession session, Runnable linkChanged) {
        this.session = session;
        this.ctx = session.ctx;
        this.links = session.links;
        this.tracker = session.tracker;
        this.linkChanged = linkChanged;
    }

    Node create() {
        // Games.
        Button install = Controls.button("Install mod…", "Put the latest BlockCompanion into a game's mods folder (or a Paper server's plugins)",
                this::install);
        list = new ItemList<GameInstance>().empty(new EmptyState(Icon.LINK, "No BlockCompanion game found.")
                .hint("Install the mod, then start Minecraft.")
                .action(Controls.button("Install mod…", "Put the latest BlockCompanion into a game's mods folder", this::install)));
        list.setItems(items);
        list.setCellFactory(v -> new Cell());
        list.visibleRows(1, 4);
        textures = Controls.button("Use its textures", "Show blocks here with the selected game's resource packs (its own and its server's)",
                this::useTextures);
        Section games = new Section("Games", list,
                Controls.hint("Tick the games your project goes to. Active games connect by themselves."), textures)
                .actions(Controls.iconButton(Icon.REFRESH, "Look for games again, ask them for their progress and chests, and count the materials again",
                        session::refreshAll));

        // Sending.
        Button send = Controls.primary("Send to game", session::send);
        send.setTooltip(new Tooltip("Send this project to the ticked games: it appears in front of you in the game."
                + " A server adds it to its shared schematics."));
        live = Controls.toggle("Live", "Send the project to the ticked games after every change, so the game follows what you build here");
        live.setSelected(links.live());
        live.selectedProperty().addListener((o, a, b) -> {
            if (b != links.live()) links.setLive(b);
        });
        Section sending = new Section("Send project", new ActionBar(send, live),
                Controls.hint("Live sends every change a moment later, so the ghosts in the game follow your edits."));

        // Game progress: the build in the game whose placed blocks count as done, and the linked chests.
        gameLink = new ComboBox<>();
        gameLink.setTooltip(new Tooltip("The build in the game (BlockCompanion) whose placed blocks count as done."
                + " Automatic picks the one loaded from this project; Not linked ignores the game."));
        gameLink.valueProperty().addListener((o, a, b) -> {
            if (updatingChoices || b == null || b.link().equals(tracker.link())) return;
            tracker.setLink(b.link());
            updateChoices();
            linkChanged.run();
        });
        Form progressForm = new Form();
        progressForm.row("Linked build", gameLink);
        gameStatus = Controls.hint("");
        chestsText = Controls.hint("");
        Section progress = new Section("Game progress", progressForm, gameStatus, chestsText);

        // The mod.
        Section mod = new Section("BlockCompanion mod", install,
                Controls.hint("The latest BlockCompanion for the game's loader and Minecraft version. Restart the game afterwards."));

        // How the link stands, at the bottom.
        banner = new Banner();
        state = new StatusBadge(Tone.NEUTRAL, "");
        stateDetail = Controls.caption("");
        stateDetail.setWrapText(true);
        VBox status = new VBox(Theme.XS, state, stateDetail);

        PanelScaffold page = new PanelScaffold()
                .add(games, sending, progress, mod)
                .footer(banner, status);
        links.addListener(listener);
        updateChoices();
        update();
        return page;
    }

    void dispose() {
        links.removeListener(listener);
    }

    private void update() {
        GameInstance sel = list.getSelectionModel().getSelectedItem();
        items.setAll(links.instances());
        if (sel != null) items.stream().filter(g -> g.id().equals(sel.id())).findFirst().ifPresent(g -> list.getSelectionModel().select(g));
        else if (!items.isEmpty()) list.getSelectionModel().select(0);
        list.refresh();
        textures.setDisable(items.isEmpty());
        Controls.show(textures, !items.isEmpty());

        LinkStatus s = LinkStatus.of(links);
        state.set(s.tone(), s.title());
        stateDetail.setText(s.detail());

        int chests = links.chestCount();
        chestsText.setText(chests > 0
                ? chests + (chests == 1 ? " linked chest counts" : " linked chests count") + " as gathered."
                : "Linked chests count as gathered: in the game, sneak and right-click a chest with the stick.");
        if (live.isSelected() != links.live()) live.setSelected(links.live());
        showGame();
    }

    // ---- game progress -----------------------------------------------------------------------------------------------

    /** Fills the game progress picker: automatic, not linked, and every build the game has written progress for. */
    void updateChoices() {
        if (gameLink == null) return;
        updatingChoices = true;
        try {
            List<Choice> choices = new ArrayList<>();
            choices.add(new Choice(Gathered.Link.AUTO, tracker.autoMatch().map(p -> "Automatic: " + p.label()).orElse("Automatic (no match yet)")));
            choices.add(new Choice(Gathered.Link.OFF, "Not linked"));
            for (GameProgress p : tracker.game().builds()) {
                String file = p.file().getFileName().toString();
                choices.add(new Choice(Gathered.Link.file(file), p.label() + " · " + hash(file)));
            }
            Gathered.Link current = tracker.link();
            if (choices.stream().noneMatch(c -> c.link().equals(current))) choices.add(new Choice(current, current.file() + " (gone)"));
            gameLink.getItems().setAll(choices);
            choices.stream().filter(c -> c.link().equals(current)).findFirst().ifPresent(gameLink::setValue);
        } finally {
            updatingChoices = false;
        }
        showGame();
    }

    /** The hash part of a progress file's name ({@code Watchtower-a1b2c3d4e5f6.json} → {@code a1b2c3d4e5f6}). */
    private static String hash(String file) {
        String stem = file.endsWith(".json") ? file.substring(0, file.length() - 5) : file;
        int dash = stem.lastIndexOf('-');
        return dash < 0 ? stem : stem.substring(dash + 1);
    }

    /** The line under the picker: how far the build is in the game, or what to do to get there. */
    void showGame() {
        if (gameStatus != null) gameStatus.setText(gameText(tracker));
    }

    /** How far the linked build is in the game, or what to do to link one. */
    static String gameText(Tracker tracker) {
        ProgressFolder.Snapshot s = tracker.game();
        GameProgress p = tracker.linked();
        Gathered.Link l = tracker.link();
        if (p != null) return p.status(Instant.now());
        if (l.mode() == Gathered.Link.Mode.OFF) return "Not linked: blocks placed in the game don't count.";
        if (!s.folderExists()) return "Install BlockCompanion and load this project in the game.";
        if (l.mode() == Gathered.Link.Mode.FILE) return "That build's progress file is gone; pick another.";
        if (s.builds().isEmpty()) return "Load this project in the game with BlockCompanion to count what's placed.";
        return "No build in the game matches this project; pick one above.";
    }

    // ---- install -----------------------------------------------------------------------------------------------------

    /** Where to install: a game seen by the link, or a folder picked by hand. */
    private record Target(String label, Path gameDir, String loader, String minecraft) {
        @Override
        public String toString() {
            return label;
        }
    }

    private void install() {
        Map<String, Target> targets = new LinkedHashMap<>();
        for (GameInstance g : links.instances()) {
            if (g.gameDir().isBlank()) continue;
            String k = g.gameDir() + "|" + g.loader() + "|" + g.minecraft();
            targets.putIfAbsent(k, new Target((g.server() ? "Server: " : "") + g.label() + " (" + g.platform() + ")", Path.of(g.gameDir()),
                    g.loader(), g.minecraft()));
        }
        Target other = new Target("Another game folder…", null, "", "");
        List<Target> choices = new ArrayList<>(targets.values());
        choices.add(other);
        ctx.ui().choose("Install BlockCompanion", "Which game gets the latest BlockCompanion?", choices, choices.getFirst()).ifPresent(t -> {
            if (t == other) installByHand();
            else installInto(t.gameDir(), t.loader(), t.minecraft(), t.label());
        });
    }

    private void installInto(Path gameDir, String loader, String minecraft, String label) {
        ctx.status("Resource Tracker: looking up the latest BlockCompanion…");
        CompletableFuture.supplyAsync(() -> {
            try {
                ModInstaller.Release r = ModInstaller.latest();
                ModInstaller.Asset a = ModInstaller.pick(r, loader, minecraft).orElseThrow(() -> new IOException(
                        "BlockCompanion " + r.tag() + " has no jar for " + minecraft + " " + loader));
                return ModInstaller.install(a, ModInstaller.folder(gameDir, loader));
            } catch (IOException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }).whenComplete((jar, err) -> ctx.runOnUiThread(() -> {
            if (err != null) banner.show(Tone.DANGER, "Install failed: " + rootMessage(err));
            else banner.show(Tone.SUCCESS, "Installed " + jar.getFileName() + " in " + label + ". Restart the game to use it.");
        }));
    }

    private void installByHand() {
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle("The game folder (the one with mods in it)");
        var dir = dc.showDialog(ctx.ui().owner());
        if (dir == null) return;
        ctx.status("Resource Tracker: looking up the latest BlockCompanion…");
        CompletableFuture.supplyAsync(() -> {
            try {
                return ModInstaller.latest();
            } catch (IOException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }).whenComplete((r, err) -> ctx.runOnUiThread(() -> {
            if (err != null) {
                banner.show(Tone.DANGER, "Install failed: " + rootMessage(err));
                return;
            }
            List<ModInstaller.Asset> jars = r.assets().stream().filter(a -> a.name().endsWith(".jar") && a.name().startsWith("blockcompanion-")
                    && !a.name().contains("-sources")).toList();
            if (jars.isEmpty()) {
                banner.show(Tone.DANGER, "BlockCompanion " + r.tag() + " has no jars");
                return;
            }
            List<String> names = jars.stream().map(ModInstaller.Asset::name).toList();
            ctx.ui().choose("Install BlockCompanion " + r.tag(), "Which one? Pick the loader and Minecraft version of that game.", names, names.getFirst())
                    .flatMap(n -> jars.stream().filter(a -> a.name().equals(n)).findFirst()).ifPresent(a -> {
                        String loader = a.name().startsWith("blockcompanion-paper-") ? "paper" : "fabric";
                        Path folder = ModInstaller.folder(dir.toPath(), loader);
                        CompletableFuture.supplyAsync(() -> {
                            try {
                                return ModInstaller.install(a, folder);
                            } catch (IOException e) {
                                throw new RuntimeException(e.getMessage(), e);
                            }
                        }).whenComplete((jar, e2) -> ctx.runOnUiThread(() -> {
                            if (e2 != null) banner.show(Tone.DANGER, "Install failed: " + rootMessage(e2));
                            else banner.show(Tone.SUCCESS, "Installed " + jar.getFileName() + " in " + folder + ". Restart the game to use it.");
                        }));
                    });
        }));
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null && (t.getMessage() == null || t instanceof java.util.concurrent.CompletionException)) t = t.getCause();
        return t.getMessage() == null ? t.toString() : t.getMessage();
    }

    // ---- textures ----------------------------------------------------------------------------------------------------

    private void useTextures() {
        GameInstance g = list.getSelectionModel().getSelectedItem();
        if (g == null) {
            ctx.toast("Pick a game in the list first");
            return;
        }
        Path cache = ctx.dataFolder().resolve("server-packs");
        Controls.busy(textures, true);
        CompletableFuture.supplyAsync(() -> {
            List<Path> packs = new ArrayList<>(GameLinks.packs(g));
            for (String p : g.serverPacks()) {
                if (!p.startsWith("http://") && !p.startsWith("https://")) continue;
                try {
                    packs.add(ModInstaller.fetchPack(p, cache));
                } catch (IOException e) {
                    throw new RuntimeException("could not download the server's pack: " + e.getMessage(), e);
                }
            }
            return packs;
        }).whenComplete((packs, err) -> ctx.runOnUiThread(() -> {
            Controls.busy(textures, false);
            if (err != null) {
                banner.show(Tone.DANGER, "Couldn't use its textures: " + rootMessage(err));
                return;
            }
            ctx.useResourcePacks(packs);
            ctx.toast(packs.isEmpty() ? "That game uses no resource packs: back to the plain textures"
                    : "Loading " + packs.size() + (packs.size() == 1 ? " resource pack" : " resource packs") + " from " + g.label());
        }));
    }

    /** One game: a box for sending to it, its name, version and what it shows, and how it stands. */
    private final class Cell extends ListCell<GameInstance> {
        private final CheckBox target = new CheckBox();
        private final Label name = new Label(), meta = new Label();
        private final StatusBadge badge = new StatusBadge(Tone.NEUTRAL, "");
        private final HBox root;
        private GameInstance bound;

        Cell() {
            getStyleClass().add("bd-list-cell");
            name.getStyleClass().add("bd-row-title");
            name.setMinWidth(0);
            name.setTextOverrun(OverrunStyle.ELLIPSIS);
            meta.getStyleClass().add("bd-row-meta");
            meta.setMinWidth(0);
            VBox text = new VBox(2, name, meta);
            text.setMinWidth(0);
            HBox.setHgrow(text, Priority.ALWAYS);
            target.setTooltip(new Tooltip("Send projects to this game"));
            target.setAccessibleText("Send projects to this game");
            target.selectedProperty().addListener((o, a, b) -> {
                if (bound != null && b != links.target(bound)) links.setTarget(bound, b);
            });
            badge.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
            root = new HBox(Theme.SM, target, text, badge);
            root.setAlignment(Pos.CENTER_LEFT);
            root.setMinWidth(0);
            setPrefWidth(0);
        }

        @Override
        protected void updateItem(GameInstance g, boolean empty) {
            super.updateItem(g, empty);
            bound = null;
            setText(null);
            if (g == null || empty) {
                setGraphic(null);
                setTooltip(null);
                return;
            }
            boolean active = g.active(Instant.now()), connected = links.connected(g);
            target.setSelected(links.target(g));
            badge.set(connected ? Tone.SUCCESS : active ? Tone.WARNING : Tone.NEUTRAL, connected ? "Connected" : active ? "Connecting…" : "Disconnected");
            name.setText((g.server() ? "Server · " : "") + g.label());
            meta.setText(g.platform() + details(g));
            setTooltip(new Tooltip(g.gameDir().isBlank() ? g.label() : g.gameDir()));
            bound = g;
            setGraphic(root);
        }

        /** " · 2 loaded, 35%" for a client, " · 3 shared, 2 players" for a server. */
        private String details(GameInstance g) {
            JsonNode s = links.status(g);
            if (s == null) return "";
            if (g.server()) {
                return " · " + s.path("schematics").size() + " shared · " + s.path("players").asInt(0) + " online";
            }
            JsonNode placements = s.path("placements");
            if (placements.isEmpty()) return " · nothing loaded";
            long total = 0, correct = 0;
            for (JsonNode p : placements) {
                total += p.path("total").asLong(0);
                correct += p.path("correct").asLong(0);
            }
            return " · " + placements.size() + " loaded" + (total > 0 ? ", " + Math.floorDiv(100 * correct, total) + "%" : "");
        }
    }
}

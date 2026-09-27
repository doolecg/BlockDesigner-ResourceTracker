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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The BlockCompanion page: every BlockCompanion game and server on this computer, each with a box for whether projects
 * go there, how it stands and whether it stays listed when it disconnects (saved); Send to game and Live (send after every change); the build in the game this project is
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
    private Label stateDetail, chestsText, gameStatus, editNote;
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
        list = new ItemList<GameInstance>().empty(new EmptyState(Icon.LINK, "No BlockCompanion game running.")
                .hint("Start Minecraft with the mod, or install it. Saved games stay here when they disconnect.")
                .action(Controls.button("Install mod…", "Put the latest BlockCompanion into a game's mods folder", this::install)));
        list.setItems(items);
        list.setCellFactory(v -> new Cell());
        list.visibleRows(3, 6);
        textures = Controls.button("Use its textures", "Show blocks here with the selected game's resource packs (its own and its server's)",
                this::useTextures);
        Section games = new Section("Games", list,
                Controls.hint("Tick the games your project goes to. Running games connect by themselves; a game leaves the list when it"
                        + " disconnects unless you save it."), textures)
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
                Controls.hint("The latest BlockCompanion for the game's loader and Minecraft version. It offers the game BlockDesigner"
                        + " takes its textures from first (Settings › Minecraft assets). Restart the game afterwards."));

        // How the link stands, at the bottom.
        banner = new Banner();
        state = new StatusBadge(Tone.NEUTRAL, "");
        stateDetail = Controls.caption("");
        stateDetail.setWrapText(true);
        editNote = Controls.caption("");
        editNote.setWrapText(true);
        VBox status = new VBox(Theme.XS, state, stateDetail, editNote);

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
        String note = links.editNote();
        editNote.setText(note == null ? "" : note);
        Controls.show(editNote, note != null);

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
        Optional<TextureGame> tex = TextureGame.find(ctx.resourcePacks());
        tex.filter(t -> t.gameDir() != null).ifPresent(t -> {
            // The game jar's version is a guess for that folder: a game seen there says what it runs.
            Optional<GameInstance> seen = t.guessed() ? seenIn(t.gameDir()) : Optional.empty();
            String mc = seen.map(GameInstance::minecraft).orElse(t.minecraft()), loader = seen.map(GameInstance::loader).orElse(t.loader());
            targets.put(targetKey(t.gameDir(), loader, mc), new Target("Textures: " + t.label() + " (" + seen.map(GameInstance::platform)
                    .orElse(t.platform()) + ")", t.gameDir(), loader, mc));
        });
        for (GameInstance g : links.instances()) {
            if (g.gameDir().isBlank()) continue;
            String k = targetKey(Path.of(g.gameDir()), g.loader(), g.minecraft());
            targets.putIfAbsent(k, new Target((g.server() ? "Server: " : "") + g.label() + " (" + g.platform() + ")", Path.of(g.gameDir()),
                    g.loader(), g.minecraft()));
        }
        Target other = new Target("Another game folder…", null, "", "");
        List<Target> choices = new ArrayList<>(targets.values());
        choices.add(other);
        boolean guess = choices.stream().anyMatch(t -> t.label().endsWith("?)"));
        ctx.ui().choose("Install BlockCompanion", "Which game gets the latest BlockCompanion?"
                        + (guess ? " A ? marks a guessed version: check that game runs it." : ""), choices, choices.getFirst()).ifPresent(t -> {
            if (t == other) installByHand();
            else installInto(t.gameDir(), t.loader(), t.minecraft(), t.label());
        });
    }

    /** The game BlockCompanion last saw in that folder, running ones first; not servers, and only when it says its version. */
    private Optional<GameInstance> seenIn(Path dir) {
        String d = targetKey(dir, "", "");
        Instant now = Instant.now();
        return links.instances().stream().filter(g -> !g.server() && !g.gameDir().isBlank() && !g.minecraft().isBlank()
                        && targetKey(Path.of(g.gameDir()), "", "").equals(d))
                .min((a, b) -> Boolean.compare(b.active(now), a.active(now)));
    }

    /** Whether a game BlockCompanion sees in that folder is running now. */
    private boolean running(Path dir) {
        String d = targetKey(dir, "", "");
        return links.instances().stream().anyMatch(g -> !g.gameDir().isBlank() && g.active(Instant.now())
                && targetKey(Path.of(g.gameDir()), "", "").equals(d));
    }

    /** One install target per folder, loader and version, however the folder is spelt. */
    private static String targetKey(Path dir, String loader, String minecraft) {
        return (dir.toAbsolutePath().normalize() + "|" + loader + "|" + minecraft).toLowerCase(java.util.Locale.ROOT);
    }

    /** The newest release, looked up in the background. */
    private CompletableFuture<ModInstaller.Release> lookUp() {
        ctx.status("Resource Tracker: looking up the latest BlockCompanion…");
        return CompletableFuture.supplyAsync(() -> {
            try {
                return ModInstaller.latest();
            } catch (IOException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        });
    }

    /**
     * Asks which loader a game runs when its mods don't tell, offering only the loaders the release has a jar for at that
     * version; no question when there's one.
     */
    private Optional<String> chooseLoader(ModInstaller.Release r, String minecraft, String label) {
        List<String> loaders = ModInstaller.loaders(r, minecraft).stream().map(ModInstaller::loaderLabel).toList();
        if (loaders.isEmpty()) {
            banner.show(Tone.DANGER, "Install failed: " + ModInstaller.missing(r, "", minecraft));
            return Optional.empty();
        }
        Optional<String> l = loaders.size() == 1 ? Optional.of(loaders.getFirst())
                : ctx.ui().choose("Install BlockCompanion", "Which loader does " + label + " run?", loaders, loaders.getFirst());
        return l.map(s -> s.toLowerCase(java.util.Locale.ROOT));
    }

    private void installInto(Path gameDir, String loader, String minecraft, String label) {
        lookUp().whenComplete((r, err) -> ctx.runOnUiThread(() -> {
            if (err != null) {
                banner.show(Tone.DANGER, "Install failed: " + rootMessage(err));
                return;
            }
            (loader.isBlank() ? chooseLoader(r, minecraft, label) : Optional.of(loader)).ifPresent(l -> {
                Optional<ModInstaller.Asset> a = ModInstaller.pick(r, l, minecraft);
                if (a.isEmpty()) banner.show(Tone.DANGER, "Install failed: " + ModInstaller.missing(r, l, minecraft));
                else download(a.get(), ModInstaller.folder(gameDir, l), gameDir, label);
            });
        }));
    }

    /** Puts the jar in the folder in the background and says how it went. */
    private void download(ModInstaller.Asset a, Path folder, Path gameDir, String label) {
        ctx.status("Resource Tracker: installing " + a.name() + "…");
        CompletableFuture.supplyAsync(() -> {
            try {
                return ModInstaller.install(a, folder);
            } catch (IOException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }).whenComplete((jar, err) -> ctx.runOnUiThread(() -> {
            if (err != null) banner.show(Tone.DANGER, "Install failed: " + inUse(err).map(u -> inUseMessage(u, gameDir)).orElse(rootMessage(err)));
            else banner.show(Tone.SUCCESS, "Installed " + jar.getFileName() + " in " + label + ". "
                    + (running(gameDir) ? "Close and restart the game to use it." : "Restart the game to use it."));
        }));
    }

    private static Optional<ModInstaller.InUse> inUse(Throwable t) {
        for (; t != null; t = t.getCause()) if (t instanceof ModInstaller.InUse u) return Optional.of(u);
        return Optional.empty();
    }

    /**
     * Why the old jar is held: the game while it runs; otherwise another program, such as BlockDesigner before 0.4.27, which
     * holds the mods of the game it takes its textures from.
     */
    private String inUseMessage(ModInstaller.InUse u, Path gameDir) {
        String jar = u.jar.getFileName().toString();
        if (running(gameDir)) return "the game is using " + jar + ": close it and install again.";
        return jar + " is in use by another program: close anything running from that game folder (a game, server or launcher)."
                + " BlockDesigner before 0.4.27 also holds it while it takes textures from that game: update BlockDesigner, or close it"
                + " and delete " + jar + " by hand.";
    }

    /** What a folder picked by hand runs, by its launcher files or its mods, and the newest release. */
    private record ByHand(ModInstaller.Release release, TextureGame game) {
    }

    /** A game folder (or its mods or plugins folder) picked by hand; the jar for what it runs is picked first when that shows. */
    private void installByHand() {
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle("The game folder (or its mods folder)");
        var dir = dc.showDialog(ctx.ui().owner());
        if (dir == null) return;
        Path chosen = dir.toPath(), gameDir = ModInstaller.gameFolder(chosen);
        Optional<GameInstance> seen = seenIn(gameDir);
        Path appData = Path.of(System.getenv().getOrDefault("APPDATA", System.getProperty("user.home")));
        lookUp().thenCombine(CompletableFuture.supplyAsync(() -> TextureGame.at(gameDir, appData)), ByHand::new)
                .whenComplete((h, err) -> ctx.runOnUiThread(() -> {
                    if (err != null) {
                        banner.show(Tone.DANGER, "Install failed: " + rootMessage(err));
                        return;
                    }
                    chooseJar(h.release(), chosen, gameDir, seen.map(GameInstance::minecraft).orElse(h.game().minecraft()),
                            seen.map(GameInstance::loader).filter(l -> !l.isBlank()).orElse(h.game().loader()));
                }));
    }

    /** Asks which jar goes into a folder picked by hand; the one for {@code minecraft} and {@code loader} first when they're known. */
    private void chooseJar(ModInstaller.Release r, Path chosen, Path gameDir, String minecraft, String loader) {
        List<ModInstaller.Jar> jars = ModInstaller.jars(r);
        if (jars.isEmpty()) {
            banner.show(Tone.DANGER, "Install failed: BlockCompanion " + r.tag() + " has no jars");
            return;
        }
        String l = loader.isEmpty() && ModInstaller.isModsFolder(chosen) && chosen.getFileName().toString().equalsIgnoreCase("plugins") ? "paper" : loader;
        // Only what the folder shows: no jar is picked for a version or loader it doesn't tell.
        Optional<ModInstaller.Asset> match = l.isEmpty() || minecraft.isEmpty() && !l.equals("paper") ? Optional.empty()
                : ModInstaller.pick(r, l, minecraft);
        String text = match.isPresent() ? "That game runs " + (minecraft + " " + ModInstaller.loaderLabel(l)).strip() + ": its jar is picked."
                : "Pick the loader and Minecraft version of that game.";
        List<String> names = jars.stream().map(j -> j.asset().name()).toList();
        ctx.ui().choose("Install BlockCompanion " + r.tag(), text, names, match.map(ModInstaller.Asset::name).orElse(null))
                .flatMap(n -> jars.stream().filter(j -> j.asset().name().equals(n)).findFirst())
                .ifPresent(j -> {
                    Path folder = ModInstaller.folderByHand(chosen, j.loader());
                    download(j.asset(), folder, gameDir, folder.toString());
                });
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
        private final ToggleButton save = new ToggleButton(null, Icon.SAVE.node(16));
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
            save.getStyleClass().addAll("flat", "bd-icon-button");
            save.setTooltip(new Tooltip("Save: keep this game in the list when it disconnects"));
            save.setAccessibleText("Keep this game in the list when it disconnects");
            save.selectedProperty().addListener((o, a, b) -> {
                if (bound != null && b != links.saved(bound)) links.setSaved(bound, b);
            });
            root = new HBox(Theme.SM, target, text, badge, save);
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
            save.setSelected(links.saved(g));
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

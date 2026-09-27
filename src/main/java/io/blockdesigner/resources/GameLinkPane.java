package io.blockdesigner.resources;

import com.fasterxml.jackson.databind.JsonNode;
import io.blockdesigner.plugin.PluginContext;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.stage.DirectoryChooser;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The Game link part of the Materials panel: every BlockCompanion game and server on this computer, active (green) or
 * disconnected (grey), each with a box for whether projects go there; Send to game, Live (send after every change),
 * Refresh, Install mod (the latest BlockCompanion into a game's mods folder) and Use its textures (the game's resource
 * packs in BlockDesigner).
 */
final class GameLinkPane {
    private final PluginContext ctx;
    private final GameLinks links;
    private final Runnable refreshAll;
    private final ObservableList<GameInstance> items = FXCollections.observableArrayList();
    private ListView<GameInstance> list;
    private Label summary;
    private ToggleButton live;
    private final GameLinks.Listener listener = this::update;

    GameLinkPane(PluginContext ctx, GameLinks links, Runnable refreshAll) {
        this.ctx = ctx;
        this.links = links;
        this.refreshAll = refreshAll;
    }

    Node create() {
        Label header = new Label("Game link");
        header.setStyle("-fx-font-weight: bold; -fx-font-size: 11px;");
        list = new ListView<>(items);
        list.setCellFactory(v -> new Cell());
        list.setPrefHeight(96);
        list.setMinHeight(52);
        list.setPlaceholder(muted(new Label("No BlockCompanion game found. Install the mod, then start Minecraft.")));

        Button send = new Button("Send to game");
        send.setTooltip(new Tooltip("Send this project to the ticked games: it appears in front of you in the game."
                + " A server adds it to its shared schematics."));
        send.setOnAction(e -> {
            int n = links.send(true, null);
            ctx.toast(n == 0 ? "No connected game is ticked" : "Sent to " + n + (n == 1 ? " game" : " games"));
        });
        live = new ToggleButton("Live");
        live.setTooltip(new Tooltip("Send the project to the ticked games after every change, so the game follows what you build here"));
        live.setSelected(links.live());
        live.selectedProperty().addListener((o, a, b) -> links.setLive(b));
        Button refresh = new Button("Refresh");
        refresh.setTooltip(new Tooltip("Look for games again, ask them for their progress, and count the materials again"));
        refresh.setOnAction(e -> {
            links.refresh();
            refreshAll.run();
        });
        Button install = new Button("Install mod…");
        install.setTooltip(new Tooltip("Put the latest BlockCompanion into a game's mods folder (or a Paper server's plugins)"));
        install.setOnAction(e -> install());
        Button textures = new Button("Use its textures");
        textures.setTooltip(new Tooltip("Show blocks here with the selected game's resource packs (its own and the server's)"));
        textures.setOnAction(e -> useTextures());
        HBox row1 = new HBox(6, send, live, refresh);
        HBox row2 = new HBox(6, install, textures);
        row1.setAlignment(Pos.CENTER_LEFT);
        row2.setAlignment(Pos.CENTER_LEFT);
        summary = muted(new Label());
        summary.setWrapText(true);
        links.addListener(listener);
        update();
        return new VBox(4, header, list, row1, row2, summary);
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
        long active = items.stream().filter(g -> g.active(Instant.now())).count();
        int chests = links.chestCount();
        summary.setText(items.isEmpty() ? "" : active + " active, " + (items.size() - active) + " disconnected"
                + (chests > 0 ? " · " + chests + (chests == 1 ? " linked chest counts" : " linked chests count") + " as gathered" : ""));
        if (live != null && live.isSelected() != links.live()) live.setSelected(links.live());
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
        ChoiceDialog<Target> d = new ChoiceDialog<>(choices.get(0), choices);
        d.setTitle("Install BlockCompanion");
        d.setHeaderText("Which game gets the latest BlockCompanion?");
        d.setContentText("Game:");
        d.showAndWait().ifPresent(t -> {
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
            if (err != null) ctx.toast("Install failed: " + rootMessage(err));
            else ctx.toast("Installed " + jar.getFileName() + " in " + label + ". Restart the game to use it.");
        }));
    }

    private void installByHand() {
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle("The game folder (the one with mods in it)");
        var window = list.getScene() == null ? null : list.getScene().getWindow();
        var dir = dc.showDialog(window);
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
                ctx.toast("Install failed: " + rootMessage(err));
                return;
            }
            List<ModInstaller.Asset> jars = r.assets().stream().filter(a -> a.name().endsWith(".jar") && a.name().startsWith("blockcompanion-")
                    && !a.name().contains("-sources")).toList();
            if (jars.isEmpty()) {
                ctx.toast("BlockCompanion " + r.tag() + " has no jars");
                return;
            }
            ChoiceDialog<String> pick = new ChoiceDialog<>(jars.get(0).name(), jars.stream().map(ModInstaller.Asset::name).toList());
            pick.setTitle("Install BlockCompanion " + r.tag());
            pick.setHeaderText("Which one? Pick the loader and Minecraft version of that game.");
            pick.showAndWait().flatMap(n -> jars.stream().filter(a -> a.name().equals(n)).findFirst()).ifPresent(a -> {
                String loader = a.name().startsWith("blockcompanion-paper-") ? "paper" : "fabric";
                Path folder = ModInstaller.folder(dir.toPath(), loader);
                CompletableFuture.supplyAsync(() -> {
                    try {
                        return ModInstaller.install(a, folder);
                    } catch (IOException e) {
                        throw new RuntimeException(e.getMessage(), e);
                    }
                }).whenComplete((jar, e2) -> ctx.runOnUiThread(() -> ctx.toast(e2 != null ? "Install failed: " + rootMessage(e2)
                        : "Installed " + jar.getFileName() + " in " + folder + ". Restart the game to use it.")));
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
        Method use;
        try {
            use = PluginContext.class.getMethod("useResourcePacks", List.class);
        } catch (NoSuchMethodException e) {
            ctx.toast("Needs BlockDesigner 0.4.24 or later");
            return;
        }
        Path cache = ctx.dataFolder().resolve("server-packs");
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
            if (err != null) {
                ctx.toast(rootMessage(err));
                return;
            }
            try {
                use.invoke(ctx, packs);
                ctx.toast(packs.isEmpty() ? "That game uses no resource packs: back to the plain textures"
                        : "Loading " + packs.size() + (packs.size() == 1 ? " resource pack" : " resource packs") + " from " + g.label());
            } catch (ReflectiveOperationException e) {
                ctx.toast("Could not switch the resource packs: " + rootMessage(e));
            }
        }));
    }

    private static Label muted(Label l) {
        l.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 11px;");
        return l;
    }

    /** One game: a box for sending to it, a dot (green: active, grey: disconnected), its name, version and what it shows. */
    private final class Cell extends ListCell<GameInstance> {
        private final CheckBox target = new CheckBox();
        private final Circle dot = new Circle(4);
        private final Label name = new Label(), meta = muted(new Label());
        private final HBox root;
        private GameInstance bound;

        Cell() {
            name.setStyle("-fx-font-weight: bold;");
            name.setMinWidth(0);
            meta.setMinWidth(0);
            VBox text = new VBox(0, name, meta);
            text.setMinWidth(0);
            HBox.setHgrow(text, Priority.ALWAYS);
            target.setTooltip(new Tooltip("Send projects to this game"));
            target.selectedProperty().addListener((o, a, b) -> {
                if (bound != null && b != links.target(bound)) links.setTarget(bound, b);
            });
            root = new HBox(8, target, dot, text);
            root.setAlignment(Pos.CENTER_LEFT);
            root.setMinWidth(0);
            setPrefWidth(0);
        }

        @Override
        protected void updateItem(GameInstance g, boolean empty) {
            super.updateItem(g, empty);
            bound = null;
            if (g == null || empty) {
                setGraphic(null);
                return;
            }
            boolean active = g.active(Instant.now()), connected = links.connected(g);
            target.setSelected(links.target(g));
            dot.setStyle("-fx-fill: " + (connected ? "#5dbe4a" : active ? "#e9cf3f" : "#8a8a8a") + ";");
            name.setText((g.server() ? "Server · " : "") + g.label());
            String state = connected ? "connected" : active ? "connecting…" : "disconnected";
            meta.setText(g.platform() + " · " + state + details(g));
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

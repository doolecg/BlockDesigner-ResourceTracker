package io.blockdesigner.resources;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.blockdesigner.core.project.ProjectFile;
import io.blockdesigner.plugin.PluginContext;
import io.blockdesigner.plugin.SceneEvent;
import javafx.animation.PauseTransition;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The live link to BlockCompanion games and servers on this computer: which ones exist (active or disconnected), a
 * connection to each active one, which ones projects go to, sending the open project (on request, on the game's Grab
 * button, and after every change while Live is on), and what the games report (their placements and linked chests).
 * JavaFX thread only, except {@link #scanFolder()}.
 */
final class GameLinks {
    /** Something changed that the panel shows. */
    interface Listener {
        void changed();
    }

    private final PluginContext ctx;
    private final Path folder;
    private final String version;
    private final List<Listener> listeners = new ArrayList<>();
    private List<GameInstance> instances = List.of();
    private final Map<String, GameConnection> connections = new HashMap<>();
    private final Map<String, JsonNode> status = new HashMap<>();
    private final Map<String, Long> retryAt = new HashMap<>();
    /** Games the user unticked, by {@link #key}: projects don't go there. */
    private final Set<String> excluded = new HashSet<>();
    private boolean live;
    private final PauseTransition liveDebounce = new PauseTransition(Duration.millis(1500));
    private Optional<Path> project = Optional.empty();
    private String projectName;

    GameLinks(PluginContext ctx, String version) {
        this(ctx, version, GameInstance.defaultFolder());
    }

    GameLinks(PluginContext ctx, String version, Path folder) {
        this.ctx = ctx;
        this.version = version;
        this.folder = folder;
        liveDebounce.setOnFinished(e -> {
            if (live) send(false, null);
        });
        ctx.on(SceneEvent.BlocksChanged.class, e -> edited());
        ctx.on(SceneEvent.LayersChanged.class, e -> edited());
    }

    void addListener(Listener l) {
        listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void fire() {
        for (Listener l : List.copyOf(listeners)) l.changed();
    }

    /** The open project (for its name and file name when sending). */
    void projectOpened(Optional<Path> file, String name) {
        project = file;
        projectName = name;
    }

    // ---- instances --------------------------------------------------------------------------------------------------

    /** Reads the instance folder; safe on any thread. */
    List<GameInstance> scanFolder() {
        return GameInstance.scan(folder, Instant.now());
    }

    /** A new look at the folder: connects to active games that aren't connected yet. */
    void scanned(List<GameInstance> list) {
        instances = List.copyOf(list);
        Instant now = Instant.now();
        long ms = System.currentTimeMillis();
        for (GameInstance g : instances) {
            GameConnection c = connections.get(g.id());
            boolean connected = c != null && c.state() != GameConnection.State.CLOSED;
            if (!g.active(now) || connected || ms < retryAt.getOrDefault(g.id(), 0L)) continue;
            connect(g);
        }
        // Forget connections to games that are gone from the folder.
        connections.keySet().removeIf(id -> instances.stream().noneMatch(g -> g.id().equals(id)));
        fire();
    }

    /** Looks at the folder now and asks every connected game for its status. */
    void refresh() {
        retryAt.clear();
        scanned(scanFolder());
        for (GameConnection c : connections.values()) c.send(c.newMessage("refresh"));
    }

    private void connect(GameInstance g) {
        GameConnection c = new GameConnection(g, version, new GameConnection.Listener() {
            public void message(GameConnection c, JsonNode m) {
                onMessage(c, m);
            }

            public void stateChanged(GameConnection c) {
                if (c.state() == GameConnection.State.CLOSED) {
                    retryAt.put(c.instance.id(), System.currentTimeMillis() + 10_000);
                    status.remove(c.instance.id());
                }
                fire();
            }
        }, ctx::runOnUiThread);
        connections.put(g.id(), c);
        c.start();
    }

    List<GameInstance> instances() {
        return instances;
    }

    /** True while connected to the game. */
    boolean connected(GameInstance g) {
        GameConnection c = connections.get(g.id());
        return c != null && c.state() == GameConnection.State.CONNECTED;
    }

    /** What a game said last (its {@code status} message), or null. */
    JsonNode status(GameInstance g) {
        return status.get(g.id());
    }

    /** Stays the same across restarts of one game (the id doesn't). */
    static String key(GameInstance g) {
        return g.kind() + "|" + g.name() + "|" + g.gameDir();
    }

    boolean target(GameInstance g) {
        return !excluded.contains(key(g));
    }

    void setTarget(GameInstance g, boolean on) {
        if (on) excluded.remove(key(g));
        else excluded.add(key(g));
        fire();
    }

    boolean live() {
        return live;
    }

    void setLive(boolean on) {
        live = on;
        if (on) send(false, null);
        fire();
    }

    private void edited() {
        if (live) liveDebounce.playFromStart();
    }

    // ---- messages ---------------------------------------------------------------------------------------------------

    private void onMessage(GameConnection c, JsonNode m) {
        switch (m.path("type").asText("")) {
            case "welcome", "status" -> {
                if (m.path("type").asText().equals("status")) status.put(c.instance.id(), m);
                fire();
            }
            case "grab" -> {
                int sent = send(true, c);
                ctx.toast(sent > 0 ? "Sent the project to " + c.instance.label() : "Open or start a project to send it to the game");
            }
            case "received" -> ctx.status("Resource Tracker: " + c.instance.label() + " has " + m.path("file").asText(""));
            case "error" -> ctx.toast(c.instance.label() + ": " + m.path("message").asText("error"));
            default -> {
                // newer message types: ignore
            }
        }
    }

    /**
     * Sends the open project to {@code only}, or to every connected, ticked game. {@code open}: the user sent it (the
     * game loads it if it isn't loaded); else it is a live update. Returns how many games it went to.
     */
    int send(boolean open, GameConnection only) {
        if (ctx.scene().layers().isEmpty()) return 0;
        List<GameConnection> to = new ArrayList<>();
        if (only != null) to.add(only);
        else for (GameInstance g : instances) {
            GameConnection c = connections.get(g.id());
            if (c != null && c.state() == GameConnection.State.CONNECTED && target(g)) to.add(c);
        }
        if (to.isEmpty()) return 0;
        byte[] data;
        try {
            data = pack();
        } catch (IOException e) {
            ctx.toast("Could not pack the project: " + e.getMessage());
            return 0;
        }
        String name = projectName != null ? projectName : "Untitled";
        String file = project.map(p -> p.getFileName().toString()).orElse(name + "." + ProjectFile.EXTENSION);
        int n = 0;
        for (GameConnection c : to) {
            ObjectNode msg = c.newMessage("project").put("file", file).put("name", name).put("open", open).put("sha256", sha256(data))
                    .put("data", Base64.getEncoder().encodeToString(data));
            if (c.send(msg)) n++;
        }
        return n;
    }

    /** The open project as a {@code .bdproj}, written the way BlockDesigner saves it. */
    private byte[] pack() throws IOException {
        Path tmp = Files.createTempFile("resource-tracker-", "." + ProjectFile.EXTENSION);
        try {
            String active = ctx.activeLayer().map(io.blockdesigner.core.model.Layer::id).orElse(null);
            ProjectFile.save(new ProjectFile.Contents(projectName != null ? projectName : "Untitled", ctx.targetVersion(), ctx.scene().layers(),
                    active, Map.of()), tmp);
            return Files.readAllBytes(tmp);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- what the games report --------------------------------------------------------------------------------------

    /** Items in the linked chests of the connected, ticked games (clients), added up. */
    Map<String, Long> chests() {
        Map<String, Long> out = new TreeMap<>();
        for (GameInstance g : instances) {
            JsonNode s = status.get(g.id());
            if (s == null || g.server() || !target(g)) continue;
            s.path("chests").path("items").properties().forEach(e -> out.merge(e.getKey(), Math.max(0, e.getValue().asLong(0)), Long::sum));
        }
        return out;
    }

    /** How many chests those games have linked. */
    int chestCount() {
        int n = 0;
        for (GameInstance g : instances) {
            JsonNode s = status.get(g.id());
            if (s != null && !g.server() && target(g)) n += s.path("chests").path("count").asInt(0);
        }
        return n;
    }

    /** The resource packs of a game, lowest priority first: its own, then what its server sent (files only). */
    static List<Path> packs(GameInstance g) {
        Map<String, Path> out = new LinkedHashMap<>();
        for (String p : g.clientPacks()) if (!p.isBlank()) out.put(p, Path.of(p));
        for (String p : g.serverPacks()) {
            if (p.isBlank() || p.startsWith("http://") || p.startsWith("https://")) continue;
            out.put(p, Path.of(p));
        }
        return out.values().stream().filter(Files::exists).toList();
    }

    void close() {
        connections.values().forEach(GameConnection::close);
        connections.clear();
        liveDebounce.stop();
    }
}

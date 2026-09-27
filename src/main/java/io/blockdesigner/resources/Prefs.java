package io.blockdesigner.resources;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The plugin's own page state, kept in its data folder as {@code settings.json}: where to count, the item list's sort
 * and Hide done, and the game link's Live switch, the games projects don't go to and the games kept in the list. Saved on every change. (Its one
 * setting, counting mobs, is on its page in BlockDesigner's Settings window; BlockDesigner remembers the page shown.)
 */
final class Prefs {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path file;
    String scope = "";
    /** Read from files before 1.3.0 only, to carry the old "Mobs" tick over to the Settings window once. */
    boolean mobs;
    /** Set once the old "Mobs" tick has been carried over. */
    boolean settingsMigrated;
    String sort = "";
    boolean hideDone;
    boolean live;
    /** Games projects don't go to, by {@link GameLinks#key}. */
    final Set<String> excluded = new TreeSet<>();
    /** Games kept in the list while disconnected, by {@link GameLinks#key}: how each was when last seen. */
    final Map<String, GameInstance> saved = new LinkedHashMap<>();

    /** Settings that are never written anywhere (tests). */
    Prefs() {
        this.file = null;
    }

    Prefs(Path dataFolder) {
        this.file = dataFolder.resolve("settings.json");
        load();
    }

    private void load() {
        if (file == null || !Files.isRegularFile(file)) return;
        try {
            JsonNode n = JSON.readTree(file.toFile());
            scope = n.path("scope").asText(scope);
            mobs = n.path("mobs").asBoolean(mobs);
            settingsMigrated = n.path("settingsMigrated").asBoolean(settingsMigrated);
            sort = n.path("sort").asText(sort);
            hideDone = n.path("hideDone").asBoolean(hideDone);
            JsonNode link = n.path("gameLink");
            live = link.path("live").asBoolean(live);
            for (JsonNode e : link.path("excluded")) if (!e.asText("").isBlank()) excluded.add(e.asText());
            for (JsonNode g : link.path("saved")) {
                GameInstance i = savedGame(g);
                saved.put(GameLinks.key(i), i);
            }
        } catch (IOException | RuntimeException e) {
            // unreadable: the defaults
        }
    }

    void save() {
        if (file == null) return;
        ObjectNode n = JSON.createObjectNode();
        n.put("scope", scope);
        n.put("settingsMigrated", settingsMigrated);
        n.put("sort", sort);
        n.put("hideDone", hideDone);
        ObjectNode link = n.putObject("gameLink");
        link.put("live", live);
        var ex = link.putArray("excluded");
        excluded.forEach(ex::add);
        var sv = link.putArray("saved");
        for (GameInstance g : saved.values()) {
            ObjectNode o = sv.addObject().put("kind", g.kind()).put("name", g.name()).put("world", g.world()).put("minecraft", g.minecraft())
                    .put("loader", g.loader()).put("modVersion", g.modVersion()).put("gameDir", g.gameDir()).put("seen", g.updated().toString());
            g.clientPacks().forEach(o.putArray("clientPacks")::add);
            g.serverPacks().forEach(o.putArray("serverPacks")::add);
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(n), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // not saved this time; the next change tries again
        }
    }

    /** A saved game as it was last seen: closed, with no port to connect to. */
    private static GameInstance savedGame(JsonNode g) {
        Instant seen;
        try {
            seen = Instant.parse(g.path("seen").asText(""));
        } catch (RuntimeException e) {
            seen = Instant.EPOCH;
        }
        String kind = g.path("kind").asText("client"), name = g.path("name").asText(""), dir = g.path("gameDir").asText("");
        return new GameInstance("saved:" + kind + "|" + name + "|" + dir, kind, name, g.path("world").asText(""), g.path("minecraft").asText(""),
                g.path("loader").asText(""), g.path("modVersion").asText(""), 0, "", strings(g.path("clientPacks")), strings(g.path("serverPacks")),
                seen, true, dir);
    }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(e -> out.add(e.asText("")));
        return List.copyOf(out);
    }

    /** {@code name} as a constant of {@code type}, or {@code fallback}. */
    static <E extends Enum<E>> E constant(Class<E> type, String name, E fallback) {
        for (E e : type.getEnumConstants()) if (e.name().equals(name)) return e;
        return fallback;
    }
}

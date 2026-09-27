package io.blockdesigner.resources;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

/**
 * The plugin's own page state, kept in its data folder as {@code settings.json}: where to count, the item list's sort
 * and Hide done, and the game link's Live switch and the games projects don't go to. Saved on every change. (Its one
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
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(n), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // not saved this time; the next change tries again
        }
    }

    /** {@code name} as a constant of {@code type}, or {@code fallback}. */
    static <E extends Enum<E>> E constant(Class<E> type, String name, E fallback) {
        for (E e : type.getEnumConstants()) if (e.name().equals(name)) return e;
        return fallback;
    }
}

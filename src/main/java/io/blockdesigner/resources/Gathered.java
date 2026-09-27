package io.blockdesigner.resources;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * What has been gathered for each project: item id → how many, and which build in the game (BlockCompanion's progress
 * file) it is linked to, kept in the plugin's data folder as {@code projects/<hash of the project's path>.json} (or
 * {@code untitled.json} for a project not saved yet).
 */
final class Gathered {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path folder;
    private Path file;
    private final Map<String, Long> counts = new LinkedHashMap<>();
    private Link link = Link.AUTO;

    /**
     * How the project is linked to a build in the game: automatically (the matching progress file), not at all, or to
     * one progress file the user picked ({@code file} is its name).
     */
    record Link(Mode mode, String file) {
        enum Mode { AUTO, OFF, FILE }

        static final Link AUTO = new Link(Mode.AUTO, null);
        static final Link OFF = new Link(Mode.OFF, null);

        static Link file(String name) {
            return new Link(Mode.FILE, name);
        }
    }

    Gathered(Path dataFolder) {
        this.folder = dataFolder.resolve("projects");
    }

    /** Switches to a project's counts (empty: an unsaved project), loading what was saved for it. */
    void open(Optional<Path> project) {
        counts.clear();
        link = Link.AUTO;
        file = folder.resolve(project.map(p -> key(p.toAbsolutePath().normalize().toString()) + ".json").orElse("untitled.json"));
        if (!Files.isRegularFile(file)) return;
        try {
            var root = JSON.readTree(file.toFile());
            for (var e : root.path("gathered").properties()) counts.put(e.getKey(), Math.max(0, e.getValue().asLong()));
            var game = root.path("gameProgress");
            String mode = game.path("mode").asText("auto"), name = game.path("file").asText("");
            if (mode.equals("off")) link = Link.OFF;
            else if (mode.equals("file") && !name.isBlank()) link = Link.file(name);
        } catch (IOException | RuntimeException e) {
            // unreadable: start again
        }
    }

    long get(String item) {
        return counts.getOrDefault(item, 0L);
    }

    void set(String item, long n) {
        if (n <= 0) counts.remove(item);
        else counts.put(item, n);
    }

    void clear() {
        counts.clear();
    }

    boolean isEmpty() {
        return counts.isEmpty();
    }

    Link link() {
        return link;
    }

    void setLink(Link link) {
        this.link = link == null ? Link.AUTO : link;
    }

    /** Writes the counts and the link; the file goes when there are no counts and the link is automatic. */
    void save(Optional<Path> project) throws IOException {
        if (file == null) return;
        if (counts.isEmpty() && link.mode() == Link.Mode.AUTO) {
            Files.deleteIfExists(file);
            return;
        }
        Files.createDirectories(folder);
        ObjectNode root = JSON.createObjectNode();
        project.ifPresent(p -> root.put("project", p.toAbsolutePath().normalize().toString()));
        ObjectNode g = root.putObject("gathered");
        counts.forEach(g::put);
        if (link.mode() != Link.Mode.AUTO) {
            ObjectNode game = root.putObject("gameProgress");
            game.put("mode", link.mode() == Link.Mode.OFF ? "off" : "file");
            if (link.file() != null) game.put("file", link.file());
        }
        Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root), StandardCharsets.UTF_8);
    }

    private static String key(String path) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(path.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 10);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(path.hashCode());
        }
    }
}

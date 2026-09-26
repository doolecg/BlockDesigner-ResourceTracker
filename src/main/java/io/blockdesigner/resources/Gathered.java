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
 * What has been gathered for each project: item id → how many, kept in the plugin's data folder as
 * {@code projects/<hash of the project's path>.json} (or {@code untitled.json} for a project not saved yet).
 */
final class Gathered {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path folder;
    private Path file;
    private final Map<String, Long> counts = new LinkedHashMap<>();

    Gathered(Path dataFolder) {
        this.folder = dataFolder.resolve("projects");
    }

    /** Switches to a project's counts (empty: an unsaved project), loading what was saved for it. */
    void open(Optional<Path> project) {
        counts.clear();
        file = folder.resolve(project.map(p -> key(p.toAbsolutePath().normalize().toString()) + ".json").orElse("untitled.json"));
        if (!Files.isRegularFile(file)) return;
        try {
            var node = JSON.readTree(file.toFile()).path("gathered");
            for (var e : node.properties()) counts.put(e.getKey(), Math.max(0, e.getValue().asLong()));
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

    /** Writes the counts; the file goes when there are none. */
    void save(Optional<Path> project) throws IOException {
        if (file == null) return;
        if (counts.isEmpty()) {
            Files.deleteIfExists(file);
            return;
        }
        Files.createDirectories(folder);
        ObjectNode root = JSON.createObjectNode();
        project.ifPresent(p -> root.put("project", p.toAbsolutePath().normalize().toString()));
        ObjectNode g = root.putObject("gathered");
        counts.forEach(g::put);
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

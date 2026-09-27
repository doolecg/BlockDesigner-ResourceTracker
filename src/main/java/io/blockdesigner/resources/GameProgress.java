package io.blockdesigner.resources;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One build's progress in the game, as BlockCompanion writes it to {@code ~/.blockcompanion/progress/<name>-<hash12>.json}:
 * how many blocks of the loaded schematic are right, wrong or missing in the world, and per item how many are needed and
 * placed. Item ids follow the same rules as {@link Items}, so they line up with the tracker's own counts.
 */
record GameProgress(Path file, String schematic, String sha256, String projectName, String world, Instant updated,
                    long total, long correct, long wrong, long missing, Map<String, Item> items) {
    /** The only format this version reads. */
    static final int FORMAT = 1;

    /** No update for this long and the game is taken to be closed (or the build left alone). */
    static final Duration STALE_AFTER = Duration.ofMinutes(5);

    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** An item of the build: how many it needs and how many are placed in the world. */
    record Item(long needed, long placed) {
    }

    /** Reads a progress file; throws for a file that is partial, corrupt or in a format this version doesn't know. */
    static GameProgress read(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        return parse(file, bytes, Files.getLastModifiedTime(file).toInstant());
    }

    /**
     * Parses a progress file's bytes. {@code fallbackUpdated} stands in for a missing or unreadable {@code updated}
     * time (the file's modification time).
     */
    static GameProgress parse(Path file, byte[] bytes, Instant fallbackUpdated) throws IOException {
        if (bytes.length == 0) throw new IOException("empty file");
        JsonNode root;
        try {
            root = JSON.readTree(bytes);
        } catch (IOException e) {
            throw new IOException("not valid JSON (partly written or damaged)", e);
        }
        if (root == null || !root.isObject()) throw new IOException("not a progress file");
        JsonNode format = root.get("format");
        if (format == null || !format.canConvertToInt()) throw new IOException("no format number");
        if (format.asInt() != FORMAT) throw new IOException("format " + format.asInt() + " is not supported (this version reads " + FORMAT + ")");
        JsonNode items = root.get("items");
        if (items == null || !items.isObject()) throw new IOException("no items");

        Map<String, Item> map = new LinkedHashMap<>();
        for (var e : items.properties()) {
            JsonNode v = e.getValue();
            if (!v.isObject()) continue;
            map.put(e.getKey(), new Item(count(v, "needed"), count(v, "placed")));
        }
        Instant updated = fallbackUpdated;
        String u = text(root, "updated");
        if (u != null) {
            try {
                updated = Instant.parse(u);
            } catch (DateTimeParseException ignored) {
                // keep the file's time
            }
        }
        return new GameProgress(file, text(root, "schematic"), text(root, "sha256"), text(root, "projectName"), text(root, "world"),
                updated, count(root, "total"), count(root, "correct"), count(root, "wrong"), count(root, "missing"), Map.copyOf(map));
    }

    private static long count(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v != null && v.canConvertToLong() ? Math.max(0, v.asLong()) : 0;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v != null && v.isTextual() && !v.asText().isBlank() ? v.asText().strip() : null;
    }

    /** How many of the item are placed in the world (0 for an item the game doesn't list). */
    long placed(String item) {
        Item i = items.get(item);
        return i == null ? 0 : i.placed();
    }

    /** The schematic's file name without any folder ({@code Watchtower.bdproj}), or null. */
    String schematicFileName() {
        if (schematic == null) return null;
        int cut = Math.max(schematic.lastIndexOf('/'), schematic.lastIndexOf('\\'));
        return schematic.substring(cut + 1);
    }

    /** Whether the game hasn't updated the file for {@link #STALE_AFTER}. */
    boolean isStale(Instant now) {
        return Duration.between(updated, now).compareTo(STALE_AFTER) >= 0;
    }

    /** A name for the build in a list: the project's name (or the schematic's), and the world. */
    String label() {
        String name = projectName != null ? projectName : schematicFileName() != null ? schematicFileName() : file.getFileName().toString();
        return world == null ? name : name + " · " + world;
    }

    /**
     * The one-line status: "In game: 35% built · 9,313 blocks left · updated 5 s ago", or "… · not seen for 10 min" once
     * stale.
     */
    String status(Instant now) {
        long pct = total == 0 ? 0 : (long) Math.floor(100.0 * correct / total);
        StringBuilder b = new StringBuilder("In game: ").append(pct).append("% built · ").append(String.format("%,d", missing))
                .append(missing == 1 ? " block left" : " blocks left");
        if (wrong > 0) b.append(" · ").append(String.format("%,d", wrong)).append(" wrong");
        Duration age = Duration.between(updated, now);
        b.append(isStale(now) ? " · not seen for " + ago(age) : " · updated " + ago(age) + " ago");
        return b.toString();
    }

    /** A short duration: "5 s", "10 min", "3 h", "2 days". */
    static String ago(Duration d) {
        long s = Math.max(0, d.toSeconds());
        if (s < 60) return s + " s";
        if (s < 3600) return s / 60 + " min";
        if (s < 86_400) return s / 3600 + " h";
        long days = s / 86_400;
        return days + (days == 1 ? " day" : " days");
    }
}

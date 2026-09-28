package io.blockdesigner.blockcompanion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * A running (or recently seen) BlockCompanion game or server on this computer, from its instance file in
 * {@code ~/.blockcompanion/instances} (see BlockCompanion's {@code docs/link-protocol.md}).
 */
record GameInstance(String id, String kind, String name, String world, String minecraft, String loader, String modVersion, int port,
                    String token, List<String> clientPacks, List<String> serverPacks, Instant updated, boolean closed, String gameDir) {
    static final int FORMAT = 1;
    /** No update for this long and the instance is taken to be gone. */
    static final Duration STALE_AFTER = Duration.ofSeconds(20);
    /** Disconnected instances stay in the list this long. */
    static final Duration SHOW_FOR = Duration.ofDays(2);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** {@code <user home>/.blockcompanion/instances}. */
    static Path defaultFolder() {
        return Path.of(System.getProperty("user.home"), ".blockcompanion", "instances");
    }

    boolean server() {
        return "server".equals(kind);
    }

    /** Running now: not closed, and written within the last 20 seconds. */
    boolean active(Instant now) {
        return !closed && Duration.between(updated, now).compareTo(STALE_AFTER) < 0;
    }

    /** "Steve · My World" for a client, "My Server" for a server; with the version and loader. */
    String label() {
        String what = server() ? (name.isBlank() ? "Server" : name) : (name.isBlank() ? "Game" : name) + (world.isBlank() ? "" : " · " + world);
        return what;
    }

    String platform() {
        String l = loader.isBlank() ? "" : Character.toUpperCase(loader.charAt(0)) + loader.substring(1);
        if (l.equals("Neoforge")) l = "NeoForge";
        return (minecraft + " " + l).strip();
    }

    static GameInstance parse(byte[] bytes) throws IOException {
        JsonNode n = JSON.readTree(bytes);
        if (n == null || n.path("format").asInt(-1) != FORMAT) throw new IOException("not an instance file");
        String id = n.path("id").asText("");
        if (id.isBlank()) throw new IOException("no id");
        return new GameInstance(id, n.path("kind").asText("client"), n.path("name").asText(""), n.path("world").asText(""),
                n.path("minecraft").asText(""), n.path("loader").asText(""), n.path("modVersion").asText(""), n.path("port").asInt(0),
                n.path("token").asText(""), strings(n.path("clientPacks")), strings(n.path("serverPacks")), instant(n.path("updated").asText("")),
                n.path("closed").asBoolean(false), n.path("gameDir").asText(""));
    }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr.isArray()) arr.forEach(e -> out.add(e.asText("")));
        return List.copyOf(out);
    }

    private static Instant instant(String s) {
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException e) {
            return Instant.EPOCH;
        }
    }

    /** Every readable instance file seen within {@link #SHOW_FOR}, the most recently updated first. */
    static List<GameInstance> scan(Path folder, Instant now) {
        List<GameInstance> out = new ArrayList<>();
        if (!Files.isDirectory(folder)) return out;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*.json")) {
            for (Path f : files) {
                try {
                    GameInstance g = parse(Files.readAllBytes(f));
                    if (Duration.between(g.updated(), now).compareTo(SHOW_FOR) < 0) out.add(g);
                } catch (IOException | RuntimeException e) {
                    // partly written or not ours
                }
            }
        } catch (IOException e) {
            return out;
        }
        out.sort((a, b) -> b.updated().compareTo(a.updated()));
        return out;
    }
}

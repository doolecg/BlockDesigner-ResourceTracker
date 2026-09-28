package io.blockdesigner.blockcompanion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.blockdesigner.plugin.OpenResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * "Edit in BlockDesigner" from a game: the {@code edit} message's file is checked against its hash, saved in a folder
 * of its own and opened as the project. On success the caller answers with the project and {@code "link": <slot>};
 * anything else is answered here with {@code {"type":"error","message":…,"slot":…}}.
 */
final class GameEdit {
    /** The files BlockDesigner opens as a project. */
    static final Set<String> EXTENSIONS = Set.of("bdproj", "schem", "litematic", "nbt");

    /** Opens a file as the project and says how it went (on the UI thread): {@code PluginContext.openFile}. */
    interface Opener {
        void open(Path file, Consumer<OpenResult> done);
    }

    /** What the game sent: the placement's slot, the file's name, the build's name and the file itself. */
    record Request(int slot, String file, String name, byte[] data) {
    }

    private GameEdit() {
    }

    /** Reads and checks an {@code edit} message; the exception's message is the short reason for the game. */
    static Request parse(JsonNode m) {
        int slot = m.path("slot").asInt(-1);
        // Only the name: no folder the game names is followed.
        String sent = m.path("file").asText("");
        String file = sent.substring(Math.max(sent.lastIndexOf('/'), sent.lastIndexOf('\\')) + 1).replaceAll("[:*?\"<>|\\p{Cntrl}]", "_").strip();
        if (file.isEmpty() || file.startsWith(".")) throw new IllegalArgumentException("The game sent no file name");
        String ext = extension(file);
        if (!EXTENSIONS.contains(ext)) throw new IllegalArgumentException("BlockDesigner can't open ." + ext + " files");
        byte[] data;
        try {
            data = Base64.getDecoder().decode(m.path("data").asText(""));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("The file didn't arrive whole");
        }
        if (data.length == 0) throw new IllegalArgumentException("The game sent an empty file");
        if (!GameLinks.sha256(data).equalsIgnoreCase(m.path("sha256").asText(""))) {
            throw new IllegalArgumentException("The file didn't arrive whole (its checksum doesn't match)");
        }
        String name = m.path("name").asText("").strip();
        if (name.isEmpty()) name = file.substring(0, file.length() - ext.length() - 1);
        return new Request(slot, file, name, data);
    }

    static String extension(String file) {
        int dot = file.lastIndexOf('.');
        return dot < 0 ? "" : file.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * Writes the file into a new folder under {@code folder}, so an edit never overwrites the open project's file (a
     * {@code .bdproj} opened from there stays its project file).
     */
    static Path save(Request r, Path folder) throws IOException {
        Files.createDirectories(folder);
        Path dir = Files.createTempDirectory(folder, "edit-");
        return Files.write(dir.resolve(r.file()), r.data());
    }

    /** The answer when the edit didn't work. */
    static ObjectNode error(GameConnection c, int slot, String message) {
        return c.newMessage("error").put("message", message).put("slot", slot);
    }

    /**
     * Handles one {@code edit}: checks and saves the file, then opens it. {@code opened} gets the request once it is
     * the project (the caller sends it back linked); a failure is answered to the game and passed to {@code failed}
     * with the request (null when the message itself was bad) and the reason. A schematic's copy is deleted once
     * opened, since the project is unsaved and doesn't need it.
     */
    static void handle(GameConnection c, JsonNode m, Path folder, Opener opener, Consumer<Request> opened, BiConsumer<Request, String> failed) {
        Request r;
        Path file;
        try {
            r = parse(m);
        } catch (IllegalArgumentException e) {
            c.send(error(c, m.path("slot").asInt(-1), e.getMessage()));
            failed.accept(null, e.getMessage());
            return;
        }
        try {
            file = save(r, folder);
        } catch (IOException e) {
            String why = "Couldn't save the file: " + e.getMessage();
            c.send(error(c, r.slot(), why));
            failed.accept(r, why);
            return;
        }
        boolean project = extension(r.file()).equals("bdproj");
        opener.open(file, result -> {
            if (!project || !result.isOpened()) deleteFolder(file.getParent());
            if (result.isOpened()) {
                opened.accept(r);
                return;
            }
            String why = result.status() == OpenResult.Status.CANCELLED ? "Cancelled in BlockDesigner" : result.message();
            c.send(error(c, r.slot(), why));
            failed.accept(r, why);
        });
    }

    private static void deleteFolder(Path dir) {
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // a leftover in the plugin's folder does no harm
                }
            });
        } catch (IOException ignored) {
            // as above
        }
    }
}

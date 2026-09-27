package io.blockdesigner.resources;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Installs BlockCompanion into a game: the jar for the game's loader and Minecraft version from the latest release on
 * GitHub, into its {@code mods} folder (or a Paper server's {@code plugins}), replacing older BlockCompanion jars there.
 */
final class ModInstaller {
    static final String REPO = "doolecg/BlockCompanion";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15)).build();

    /** A file of a release. */
    record Asset(String name, String url, long size) {
    }

    record Release(String tag, List<Asset> assets) {
    }

    private ModInstaller() {
    }

    /** The latest release of BlockCompanion. */
    static Release latest() throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/" + REPO + "/releases/latest"))
                .header("Accept", "application/vnd.github+json").header("User-Agent", "BlockDesigner-ResourceTracker").timeout(Duration.ofSeconds(30))
                .build();
        HttpResponse<byte[]> r;
        try {
            r = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
        if (r.statusCode() == 404) throw new IOException("BlockCompanion has no release yet");
        if (r.statusCode() != 200) throw new IOException("GitHub answered " + r.statusCode());
        return parse(r.body());
    }

    static Release parse(byte[] json) throws IOException {
        JsonNode n = JSON.readTree(json);
        List<Asset> assets = new ArrayList<>();
        for (JsonNode a : n.path("assets")) {
            assets.add(new Asset(a.path("name").asText(""), a.path("browser_download_url").asText(""), a.path("size").asLong(0)));
        }
        return new Release(n.path("tag_name").asText(""), List.copyOf(assets));
    }

    /**
     * The jar for a loader and Minecraft version: {@code blockcompanion-<loader>-<minecraft>-<version>.jar}, or
     * {@code blockcompanion-paper-<version>.jar} for Paper.
     */
    static Optional<Asset> pick(Release release, String loader, String minecraft) {
        String l = loader.toLowerCase(Locale.ROOT);
        String prefix = l.equals("paper") ? "blockcompanion-paper-" : "blockcompanion-" + l + "-" + minecraft + "-";
        return release.assets().stream()
                .filter(a -> a.name().toLowerCase(Locale.ROOT).startsWith(prefix) && a.name().endsWith(".jar"))
                .filter(a -> !a.name().contains("-sources") && !a.name().contains("-dev"))
                .findFirst();
    }

    /** Where the jar goes: {@code plugins} for Paper, {@code mods} otherwise. */
    static Path folder(Path gameDir, String loader) {
        return gameDir.resolve("paper".equalsIgnoreCase(loader) ? "plugins" : "mods");
    }

    /** Downloads the jar into the folder, then removes other {@code blockcompanion-*.jar} files there. */
    static Path install(Asset asset, Path folder) throws IOException {
        Files.createDirectories(folder);
        Path target = folder.resolve(asset.name());
        Path part = folder.resolve(asset.name() + ".part");
        HttpRequest req = HttpRequest.newBuilder(URI.create(asset.url())).header("User-Agent", "BlockDesigner-ResourceTracker")
                .timeout(Duration.ofMinutes(5)).build();
        try {
            HttpResponse<Path> r = HTTP.send(req, HttpResponse.BodyHandlers.ofFile(part));
            if (r.statusCode() != 200) throw new IOException("download answered " + r.statusCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Files.deleteIfExists(part);
            throw new IOException("interrupted");
        } catch (IOException e) {
            Files.deleteIfExists(part);
            throw e;
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        removeOthers(folder, target);
        return target;
    }

    /** Other BlockCompanion jars in the folder (older versions), removed so only one loads. */
    static void removeOthers(Path folder, Path keep) throws IOException {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "blockcompanion-*.jar")) {
            for (Path f : files) if (!f.getFileName().equals(keep.getFileName())) Files.deleteIfExists(f);
        }
    }

    /** Downloads a server's resource pack (a URL) into {@code cache}, once per URL. */
    static Path fetchPack(String url, Path cache) throws IOException {
        Files.createDirectories(cache);
        Path target = cache.resolve(GameLinks.sha256(url.getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0, 16) + ".zip");
        if (Files.isRegularFile(target)) return target;
        Path part = cache.resolve(target.getFileName() + ".part");
        try {
            HttpResponse<Path> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "BlockDesigner-ResourceTracker")
                    .timeout(Duration.ofMinutes(5)).build(), HttpResponse.BodyHandlers.ofFile(part));
            if (r.statusCode() != 200) throw new IOException("the pack download answered " + r.statusCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Files.deleteIfExists(part);
            throw new IOException("interrupted");
        } catch (IOException e) {
            Files.deleteIfExists(part);
            throw e;
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }
}

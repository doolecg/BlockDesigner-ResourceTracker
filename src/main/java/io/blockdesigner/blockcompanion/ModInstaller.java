package io.blockdesigner.blockcompanion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Installs BlockCompanion into a game: the jar for the game's loader and Minecraft version from the newest release on
 * GitHub, into its {@code mods} folder (or a Paper server's {@code plugins}), replacing older BlockCompanion jars there.
 * The download is checked against the size and SHA-256 GitHub gives for it.
 */
final class ModInstaller {
    static final String REPO = "doolecg/BlockCompanion";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15)).build();
    /** {@code blockcompanion-<loader>-<minecraft>-<version>.jar} or {@code blockcompanion-paper-<version>.jar}, lowercased; no classifier. */
    private static final Pattern JAR = Pattern.compile("blockcompanion-(fabric|neoforge|paper)-(?:(\\d+\\.\\d+(?:\\.\\d+)?)-)?([^-]+)\\.jar");
    private static final Pattern VERSION = Pattern.compile("^v?(\\d+(?:\\.\\d+)*)");
    private static final Pattern NEO_ID = Pattern.compile("modId\\s*=\\s*\"blockcompanion\"");
    private static final Pattern PLUGIN_NAME = Pattern.compile("(?m)^name:\\s*['\"]?blockcompanion['\"]?\\s*$", Pattern.CASE_INSENSITIVE);

    /** A file of a release; {@code digest} is GitHub's {@code "sha256:<hex>"}, or "" when it gives none. */
    record Asset(String name, String url, long size, String digest) {
        Asset(String name, String url, long size) {
            this(name, url, size, "");
        }
    }

    record Release(String tag, List<Asset> assets) {
        /** "BlockCompanion 0.4.0". */
        String title() {
            return "BlockCompanion " + tag;
        }
    }

    /** A mod jar of a release, told by its name: loader "fabric", "neoforge" or "paper"; minecraft "" for Paper. */
    record Jar(Asset asset, String loader, String minecraft) {
    }

    private ModInstaller() {
    }

    /** The newest release of BlockCompanion: the highest version among the last 20, drafts and prereleases left out. */
    static Release latest() throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/" + REPO + "/releases?per_page=20"))
                .header("Accept", "application/vnd.github+json").header("User-Agent", "BlockDesigner-BlockCompanionPlugin").timeout(Duration.ofSeconds(30))
                .build();
        HttpResponse<byte[]> r;
        try {
            r = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
        if (r.statusCode() != 200) {
            throw new IOException(error(r.statusCode(), r.headers().firstValue("X-RateLimit-Remaining").orElse(""),
                    r.headers().firstValue("X-RateLimit-Reset").orElse(""), ZoneId.systemDefault()));
        }
        return newest(r.body()).orElseThrow(() -> new IOException("BlockCompanion has no release yet"));
    }

    /** What to say when GitHub answers {@code status}; {@code remaining} and {@code reset} are its rate limit headers. */
    static String error(int status, String remaining, String reset, ZoneId zone) {
        if (status == 429 || status == 403 && remaining.strip().equals("0")) {
            try {
                String at = DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochSecond(Long.parseLong(reset.strip())).atZone(zone));
                return "GitHub's rate limit is reached; try again at " + at;
            } catch (RuntimeException e) {
                return "GitHub's rate limit is reached; try again later";
            }
        }
        if (status == 404) return "BlockCompanion's releases couldn't be found on GitHub";
        return "GitHub answered " + status;
    }

    /** The highest version in a list of releases ({@code GET /releases}), drafts and prereleases left out. */
    static Optional<Release> newest(byte[] json) throws IOException {
        JsonNode best = null;
        for (JsonNode n : JSON.readTree(json)) {
            if (n.path("draft").asBoolean(false) || n.path("prerelease").asBoolean(false)) continue;
            if (best == null || compareVersions(n.path("tag_name").asText(""), best.path("tag_name").asText("")) > 0) best = n;
        }
        return best == null ? Optional.empty() : Optional.of(release(best));
    }

    /** One release ({@code GET /releases/<id>}). */
    static Release parse(byte[] json) throws IOException {
        return release(JSON.readTree(json));
    }

    private static Release release(JsonNode n) {
        List<Asset> assets = new ArrayList<>();
        for (JsonNode a : n.path("assets")) {
            JsonNode digest = a.path("digest");
            assets.add(new Asset(a.path("name").asText(""), a.path("browser_download_url").asText(""), a.path("size").asLong(0),
                    digest.isTextual() ? digest.asText() : ""));
        }
        return new Release(n.path("tag_name").asText(""), List.copyOf(assets));
    }

    /** "0.10.0" after "0.9.2": number by number; a leading "v" and anything after the numbers don't count. */
    static int compareVersions(String a, String b) {
        int[] x = numbers(a), y = numbers(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int c = Integer.compare(i < x.length ? x[i] : 0, i < y.length ? y[i] : 0);
            if (c != 0) return c;
        }
        return 0;
    }

    private static int[] numbers(String v) {
        Matcher m = VERSION.matcher(v.strip().toLowerCase(Locale.ROOT));
        if (!m.find()) return new int[0];
        String[] parts = m.group(1).split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                out[i] = Integer.MAX_VALUE;
            }
        }
        return out;
    }

    /** The asset as a mod jar, when its name is one (not a sources or dev jar), whatever its case. */
    static Optional<Jar> jar(Asset a) {
        Matcher m = JAR.matcher(a.name().toLowerCase(Locale.ROOT));
        if (!m.matches()) return Optional.empty();
        String loader = m.group(1), mc = m.group(2) == null ? "" : m.group(2);
        if (loader.equals("paper") != mc.isEmpty()) return Optional.empty();
        return Optional.of(new Jar(a, loader, mc));
    }

    /** The release's mod jars, in its order. */
    static List<Jar> jars(Release release) {
        return release.assets().stream().map(ModInstaller::jar).flatMap(Optional::stream).toList();
    }

    /**
     * The jar for a loader and Minecraft version: {@code blockcompanion-<loader>-<minecraft>-<version>.jar}, else the one
     * for an earlier version of the same line (26.2 for 26.2.1, 1.21.1 for 1.21.4), which the mod accepts; or
     * {@code blockcompanion-paper-<version>.jar} for Paper.
     */
    static Optional<Asset> pick(Release release, String loader, String minecraft) {
        String l = loader.toLowerCase(Locale.ROOT);
        List<Jar> mine = jars(release).stream().filter(j -> j.loader().equals(l)).toList();
        if (l.equals("paper")) return mine.stream().findFirst().map(Jar::asset);
        Optional<Jar> exact = mine.stream().filter(j -> j.minecraft().equals(minecraft)).findFirst();
        if (exact.isPresent()) return exact.map(Jar::asset);
        return mine.stream().filter(j -> fits(j.minecraft(), minecraft))
                .max(Comparator.comparing(Jar::minecraft, ModInstaller::compareVersions)).map(Jar::asset);
    }

    /** Whether a jar built for {@code jar} runs on {@code game}: the same major and minor, and not newer (26.2 on 26.2.1). */
    static boolean fits(String jar, String game) {
        if (jar.equals(game)) return true;
        int[] x = numbers(jar), y = numbers(game);
        return x.length >= 2 && y.length >= 2 && x[0] == y[0] && x[1] == y[1] && compareVersions(jar, game) <= 0;
    }

    /** The loaders ("fabric", "neoforge") the release has a jar for at that Minecraft version. */
    static List<String> loaders(Release release, String minecraft) {
        return List.of("fabric", "neoforge").stream().filter(l -> pick(release, l, minecraft).isPresent()).toList();
    }

    /** "BlockCompanion 0.2.0 has no NeoForge jar for 26.2; it has Fabric for 26.2": what the release has instead. */
    static String missing(Release release, String loader, String minecraft) {
        List<Jar> jars = jars(release);
        if (jars.isEmpty()) return release.title() + " has no jars";
        List<Jar> sameVersion = jars.stream().filter(j -> fits(j.minecraft(), minecraft)).toList();
        List<Jar> sameLoader = jars.stream().filter(j -> j.loader().equalsIgnoreCase(loader)).toList();
        List<Jar> shown = !sameVersion.isEmpty() ? sameVersion : !sameLoader.isEmpty() ? sameLoader : jars;
        Map<String, Set<String>> byLoader = new LinkedHashMap<>();
        for (Jar j : shown) byLoader.computeIfAbsent(loaderLabel(j.loader()), k -> new LinkedHashSet<>()).add(j.minecraft());
        List<String> has = new ArrayList<>();
        byLoader.forEach((l, versions) -> {
            versions.remove("");
            has.add(versions.isEmpty() ? l : l + " for " + String.join(", ", versions));
        });
        return release.title() + " has no " + (loader.isBlank() ? "" : loaderLabel(loader) + " ") + "jar for " + minecraft
                + "; it has " + String.join("; ", has);
    }

    /** "fabric" → Fabric, "neoforge" → NeoForge, "paper" → Paper. */
    static String loaderLabel(String loader) {
        return switch (loader.toLowerCase(Locale.ROOT)) {
            case "neoforge" -> "NeoForge";
            case "fabric" -> "Fabric";
            case "paper" -> "Paper";
            default -> loader;
        };
    }

    /** Where the jar goes: {@code plugins} for Paper, {@code mods} otherwise. */
    static Path folder(Path gameDir, String loader) {
        return gameDir.resolve("paper".equalsIgnoreCase(loader) ? "plugins" : "mods");
    }

    /** A folder picked by hand that is a {@code mods} or {@code plugins} folder itself, not the game folder. */
    static boolean isModsFolder(Path chosen) {
        String n = chosen.getFileName() == null ? "" : chosen.getFileName().toString();
        return n.equalsIgnoreCase("mods") || n.equalsIgnoreCase("plugins");
    }

    /** The game folder of a folder picked by hand: the one above a {@code mods} or {@code plugins} folder. */
    static Path gameFolder(Path chosen) {
        return isModsFolder(chosen) && chosen.getParent() != null ? chosen.getParent() : chosen;
    }

    /** Where the jar goes in a folder picked by hand: into it when it's a {@code mods} or {@code plugins} folder. */
    static Path folderByHand(Path chosen, String loader) {
        return isModsFolder(chosen) ? chosen : folder(chosen, loader);
    }

    /**
     * Downloads the jar, removes the other BlockCompanion jars in the folder (and a Paper server's {@code plugins/update}),
     * then puts the new one in, so there are never two. Fails with the old jars left when one can't be removed.
     */
    static Path install(Asset asset, Path folder) throws IOException {
        Files.createDirectories(folder);
        Path target = folder.resolve(asset.name());
        Path part = folder.resolve(asset.name() + ".part");
        HttpRequest req = HttpRequest.newBuilder(URI.create(asset.url())).header("User-Agent", "BlockDesigner-BlockCompanionPlugin")
                .timeout(Duration.ofMinutes(5)).build();
        try {
            HttpResponse<Path> r = HTTP.send(req, HttpResponse.BodyHandlers.ofFile(part));
            if (r.statusCode() != 200) throw new IOException("download answered " + r.statusCode());
            String bad = check(part, asset);
            if (bad != null) throw new IOException(asset.name() + " didn't download right (" + bad + "); nothing was changed, try again");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Files.deleteIfExists(part);
            throw new IOException("interrupted");
        } catch (IOException e) {
            Files.deleteIfExists(part);
            throw e;
        }
        try {
            removeOthers(folder, null);
            if (folder.getFileName() != null && folder.getFileName().toString().equalsIgnoreCase("plugins")) {
                removeOthers(folder.resolve("update"), null);
            }
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(part);
            throw e;
        }
        return target;
    }

    /**
     * Why a downloaded file isn't the release's file, or null when it is: its size and its {@code digest}
     * ({@code "sha256:<hex>"}, also sha512 or sha1) against what the release says. A check the release doesn't give is skipped.
     */
    static String check(Path file, Asset asset) throws IOException {
        long size = Files.size(file);
        if (asset.size() > 0 && size != asset.size()) return size + " bytes instead of " + asset.size();
        int colon = asset.digest().indexOf(':');
        if (colon < 0) return null;
        String algorithm = switch (asset.digest().substring(0, colon).toLowerCase(Locale.ROOT)) {
            case "sha256" -> "SHA-256";
            case "sha512" -> "SHA-512";
            case "sha1" -> "SHA-1";
            default -> null;
        };
        if (algorithm == null) return null;
        java.security.MessageDigest md;
        try {
            md = java.security.MessageDigest.getInstance(algorithm);
        } catch (java.security.NoSuchAlgorithmException e) {
            return null;
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
        }
        String got = java.util.HexFormat.of().formatHex(md.digest());
        return got.equalsIgnoreCase(asset.digest().substring(colon + 1).strip()) ? null : "its checksum doesn't match";
    }

    /**
     * Every BlockCompanion jar in the folder but {@code keep} (may be null), told by its name or by the mod id in its
     * {@code fabric.mod.json}, {@code neoforge.mods.toml} or {@code plugin.yml}: deleted, or renamed to {@code .disabled}
     * when the game holds it open. Disabled jars left from before go when they can.
     */
    static void removeOthers(Path folder, Path keep) throws IOException {
        if (!Files.isDirectory(folder)) return;
        List<Path> found = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder)) {
            for (Path f : files) {
                String n = f.getFileName().toString().toLowerCase(Locale.ROOT);
                if (keep != null && f.getFileName().equals(keep.getFileName()) || !Files.isRegularFile(f)) continue;
                if (n.startsWith("blockcompanion") && n.endsWith(".jar.disabled")) {
                    try {
                        Files.deleteIfExists(f);
                    } catch (IOException e) {
                        // still open in the game; it doesn't load anyway
                    }
                } else if (n.endsWith(".jar") && (n.startsWith("blockcompanion") || isBlockCompanion(f))) {
                    found.add(f);
                }
            }
        }
        for (Path f : found) {
            try {
                Files.deleteIfExists(f);
            } catch (IOException e) {
                try {
                    Files.move(f, f.resolveSibling(f.getFileName() + ".disabled"), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e2) {
                    throw new InUse(f, e2);
                }
            }
        }
    }

    /** An old BlockCompanion jar another program holds open, so it can be neither deleted nor renamed. */
    static final class InUse extends IOException {
        final Path jar;

        InUse(Path jar, IOException cause) {
            super(jar.getFileName() + " is in use: close the game first", cause);
            this.jar = jar;
        }
    }

    /** Whether the jar's metadata says it's BlockCompanion: mod id or plugin name {@code blockcompanion}. */
    static boolean isBlockCompanion(Path jar) {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            String fabric = entry(z, "fabric.mod.json");
            if (fabric != null && JSON.readTree(fabric).path("id").asText("").equalsIgnoreCase("blockcompanion")) return true;
            String neo = entry(z, "META-INF/neoforge.mods.toml");
            if (neo != null && NEO_ID.matcher(neo).find()) return true;
            String plugin = entry(z, "plugin.yml");
            return plugin != null && PLUGIN_NAME.matcher(plugin).find();
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** A small text file in a zip, or null. */
    private static String entry(ZipFile z, String name) throws IOException {
        ZipEntry e = z.getEntry(name);
        if (e == null || e.getSize() > 256 * 1024) return null;
        try (InputStream in = z.getInputStream(e)) {
            return new String(in.readNBytes(256 * 1024), StandardCharsets.UTF_8);
        }
    }

    /** Downloads a server's resource pack (a URL) into {@code cache}, once per URL. */
    static Path fetchPack(String url, Path cache) throws IOException {
        Files.createDirectories(cache);
        Path target = cache.resolve(GameLinks.sha256(url.getBytes(StandardCharsets.UTF_8)).substring(0, 16) + ".zip");
        if (Files.isRegularFile(target)) return target;
        Path part = cache.resolve(target.getFileName() + ".part");
        try {
            HttpResponse<Path> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "BlockDesigner-BlockCompanionPlugin")
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

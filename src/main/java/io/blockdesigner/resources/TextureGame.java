package io.blockdesigner.resources;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.blockdesigner.core.version.McVersion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/**
 * The game BlockDesigner takes its textures from (Settings › Minecraft assets): the Minecraft version of its game jar,
 * and the game folder and loader of the launcher instance whose mods and packs it uses, or of the folder its resource
 * packs are in, or the official launcher's {@code .minecraft}. Install mod offers it first.
 *
 * @param gameDir null when no game folder goes with the jar
 * @param loader  "fabric", "neoforge", or "" when it can't be told
 */
record TextureGame(String label, String minecraft, Path gameDir, String loader) {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern VERSION = Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)?)");

    /** From BlockDesigner's settings file and the resource packs loaded now. */
    static Optional<TextureGame> find(List<Path> packs) {
        String dataDir = System.getProperty("blockdesigner.dataDir");
        String appData = System.getenv().getOrDefault("APPDATA", System.getProperty("user.home"));
        Path settings = (dataDir != null && !dataDir.isBlank() ? Path.of(dataDir) : Path.of(appData, "BlockDesigner")).resolve("settings.json");
        try {
            return of(JSON.readTree(settings.toFile()), packs, Path.of(appData), Path.of(System.getProperty("user.home")));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** From BlockDesigner's settings: its {@code gameJar} and {@code instanceName}. */
    static Optional<TextureGame> of(JsonNode settings, List<Path> packs, Path appData, Path home) {
        String jarText = settings.path("gameJar").asText("");
        if (jarText.isBlank()) return Optional.empty();
        Path jar = Path.of(jarText);
        String mc = version(jar);
        if (mc.isEmpty()) return Optional.empty();
        String instance = settings.path("instanceName").asText("");

        if (!instance.isBlank()) {
            Optional<TextureGame> inst = instance(instance, appData, home);
            if (inst.isPresent()) {
                TextureGame t = inst.get();
                return Optional.of(t.minecraft().isEmpty() ? new TextureGame(t.label(), mc, t.gameDir(), t.loader()) : t);
            }
        }
        for (Path p : packs) {
            Path dir = p.toAbsolutePath().getParent();
            if (dir != null && dir.getFileName() != null && dir.getFileName().toString().equalsIgnoreCase("resourcepacks") && dir.getParent() != null) {
                Path game = dir.getParent();
                return Optional.of(new TextureGame(name(game, appData), mc, game, loaderFromMods(game)));
            }
        }
        Path dotMc = appData.resolve(".minecraft");
        if (jar.toAbsolutePath().startsWith(dotMc)) return Optional.of(new TextureGame("Minecraft Launcher", mc, dotMc, loaderFromMods(dotMc)));
        return Optional.of(new TextureGame("Minecraft " + mc, mc, null, ""));
    }

    /** A launcher instance by name, as BlockDesigner's asset setup lists them. */
    static Optional<TextureGame> instance(String name, Path appData, Path home) {
        for (String launcher : List.of("PrismLauncher", "MultiMC")) {
            Path inst = appData.resolve(launcher).resolve("instances").resolve(name);
            Path pack = inst.resolve("mmc-pack.json");
            if (!Files.isRegularFile(pack)) continue;
            String mc = "", loader = "";
            try {
                for (JsonNode c : JSON.readTree(pack.toFile()).path("components")) {
                    String uid = c.path("uid").asText("");
                    if (uid.equals("net.minecraft")) mc = c.path("version").asText("");
                    else if (uid.equals("net.fabricmc.fabric-loader") || uid.equals("org.quiltmc.quilt-loader")) loader = "fabric";
                    else if (uid.equals("net.neoforged")) loader = "neoforge";
                }
            } catch (IOException | RuntimeException e) {
                continue;
            }
            Path game = Files.isDirectory(inst.resolve("minecraft")) ? inst.resolve("minecraft") : inst.resolve(".minecraft");
            return Optional.of(new TextureGame(name, mc, game, loader.isEmpty() ? loaderFromMods(game) : loader));
        }
        Path cf = home.resolve("curseforge/minecraft/Instances").resolve(name);
        if (Files.isRegularFile(cf.resolve("minecraftinstance.json"))) {
            try {
                JsonNode n = JSON.readTree(cf.resolve("minecraftinstance.json").toFile());
                String loader = loaderName(n.path("baseModLoader").path("name").asText(""));
                return Optional.of(new TextureGame(name, n.path("gameVersion").asText(""), cf, loader.isEmpty() ? loaderFromMods(cf) : loader));
            } catch (IOException | RuntimeException e) {
                // unreadable: try the others
            }
        }
        Path mr = appData.resolve("ModrinthApp/profiles").resolve(name);
        if (Files.isDirectory(mr)) {
            String mc = "", loader = "";
            try {
                if (Files.isRegularFile(mr.resolve("profile.json"))) {
                    JsonNode m = JSON.readTree(mr.resolve("profile.json").toFile()).path("metadata");
                    mc = m.path("game_version").asText("");
                    loader = loaderName(m.path("loader").asText(""));
                }
            } catch (IOException | RuntimeException e) {
                // the mods tell
            }
            return Optional.of(new TextureGame(name, mc, mr, loader.isEmpty() ? loaderFromMods(mr) : loader));
        }
        return Optional.empty();
    }

    /** "neoforge-21.1.77" → neoforge, "fabric-0.16.5" → fabric; else "". */
    static String loaderName(String s) {
        String l = s.toLowerCase(Locale.ROOT);
        if (l.startsWith("neoforge")) return "neoforge";
        if (l.startsWith("fabric") || l.startsWith("quilt")) return "fabric";
        return "";
    }

    /** The loader the game's mods are made for, by their metadata files; "" without mods or when mixed evenly. */
    static String loaderFromMods(Path gameDir) {
        Path mods = gameDir.resolve("mods");
        if (!Files.isDirectory(mods)) return "";
        int fabric = 0, neo = 0;
        try (Stream<Path> s = Files.list(mods)) {
            for (Path jar : s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")).limit(40).toList()) {
                try (ZipFile z = new ZipFile(jar.toFile())) {
                    if (z.getEntry("fabric.mod.json") != null) fabric++;
                    if (z.getEntry("META-INF/neoforge.mods.toml") != null) neo++;
                } catch (IOException e) {
                    // not a jar
                }
            }
        } catch (IOException e) {
            return "";
        }
        return fabric > neo ? "fabric" : neo > fabric ? "neoforge" : "";
    }

    /** The Minecraft version of a client jar: read from the jar, else from its file or folder name. */
    static String version(Path jar) {
        try {
            if (Files.isRegularFile(jar)) return McVersion.fromClientJar(jar).id();
        } catch (IOException | RuntimeException e) {
            // go by the name
        }
        Matcher m = VERSION.matcher(jar.getFileName() == null ? "" : jar.getFileName().toString());
        return m.find() ? m.group(1) : "";
    }

    /** The folder's name, or its instance's for a {@code minecraft} folder inside one. */
    private static String name(Path game, Path appData) {
        if (game.equals(appData.resolve(".minecraft"))) return "Minecraft Launcher";
        String n = game.getFileName() == null ? game.toString() : game.getFileName().toString();
        boolean inner = n.equalsIgnoreCase(".minecraft") || n.equals("minecraft");
        return inner && game.getParent() != null && game.getParent().getFileName() != null ? game.getParent().getFileName().toString() : n;
    }

    /** "1.21.1 Fabric", as the games list shows platforms. */
    String platform() {
        String l = loader.equals("neoforge") ? "NeoForge" : loader.equals("fabric") ? "Fabric" : "";
        return (minecraft + " " + l).strip();
    }
}

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
 * @param guessed the version is the game jar's, which that game may not run (no launcher instance says)
 */
record TextureGame(String label, String minecraft, Path gameDir, String loader, boolean guessed) {
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
                return Optional.of(t.minecraft().isEmpty() ? new TextureGame(t.label(), mc, t.gameDir(), t.loader(), true) : t);
            }
        }
        for (Path p : packs) {
            Path dir = p.toAbsolutePath().getParent();
            if (dir != null && dir.getFileName() != null && dir.getFileName().toString().equalsIgnoreCase("resourcepacks") && dir.getParent() != null) {
                Path game = dir.getParent();
                return Optional.of(new TextureGame(name(game, appData), mc, game, loaderFromMods(game), true));
            }
        }
        Path dotMc = appData.resolve(".minecraft");
        if (jar.toAbsolutePath().startsWith(dotMc)) return Optional.of(new TextureGame("Minecraft Launcher", mc, dotMc, loaderFromMods(dotMc), true));
        return Optional.of(new TextureGame("Minecraft " + mc, mc, null, "", false));
    }

    /** A launcher instance by name, as BlockDesigner's asset setup lists them. */
    static Optional<TextureGame> instance(String name, Path appData, Path home) {
        for (String launcher : List.of("PrismLauncher", "MultiMC")) {
            Path inst = appData.resolve(launcher).resolve("instances").resolve(name);
            String[] p = mmcPack(inst.resolve("mmc-pack.json"));
            if (p == null) continue;
            Path game = Files.isDirectory(inst.resolve("minecraft")) ? inst.resolve("minecraft") : inst.resolve(".minecraft");
            return Optional.of(new TextureGame(name, p[0], game, p[1].isEmpty() ? loaderFromMods(game) : p[1], false));
        }
        Path cf = home.resolve("curseforge/minecraft/Instances").resolve(name);
        String[] c = curseForge(cf);
        if (c != null) return Optional.of(new TextureGame(name, c[0], cf, c[1].isEmpty() ? loaderFromMods(cf) : c[1], false));
        Path mr = appData.resolve("ModrinthApp/profiles").resolve(name);
        if (Files.isDirectory(mr)) {
            String[] m = modrinth(mr);
            return Optional.of(new TextureGame(name, m[0], mr, m[1].isEmpty() ? loaderFromMods(mr) : m[1], false));
        }
        return Optional.empty();
    }

    /**
     * A game folder picked by hand: its Minecraft version and loader from the launcher files around it (Prism or MultiMC's
     * {@code mmc-pack.json} one up, CurseForge's {@code minecraftinstance.json}, Modrinth's {@code profile.json}), else
     * the loader from its mods (or Paper, for a server with a Paper jar); "" for what can't be told.
     */
    static TextureGame at(Path gameDir, Path appData) {
        String mc = "", loader = "";
        String[] p = gameDir.getParent() == null ? null : mmcPack(gameDir.getParent().resolve("mmc-pack.json"));
        if (p == null) p = curseForge(gameDir);
        if (p == null && Files.isRegularFile(gameDir.resolve("profile.json"))) p = modrinth(gameDir);
        if (p != null) {
            mc = p[0];
            loader = p[1];
        }
        if (loader.isEmpty()) loader = loaderFromMods(gameDir);
        if (loader.isEmpty() && isPaper(gameDir)) loader = "paper";
        return new TextureGame(name(gameDir, appData), mc, gameDir, loader, false);
    }

    /** The Minecraft version and loader in a Prism or MultiMC {@code mmc-pack.json}; null without a readable one. */
    private static String[] mmcPack(Path pack) {
        if (!Files.isRegularFile(pack)) return null;
        String mc = "", loader = "";
        try {
            for (JsonNode c : JSON.readTree(pack.toFile()).path("components")) {
                String uid = c.path("uid").asText("");
                if (uid.equals("net.minecraft")) mc = c.path("version").asText("");
                else if (uid.equals("net.fabricmc.fabric-loader") || uid.equals("org.quiltmc.quilt-loader")) loader = "fabric";
                else if (uid.equals("net.neoforged")) loader = "neoforge";
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return new String[]{mc, loader};
    }

    /** The Minecraft version and loader of a CurseForge instance folder; null without a readable one. */
    private static String[] curseForge(Path dir) {
        Path f = dir.resolve("minecraftinstance.json");
        if (!Files.isRegularFile(f)) return null;
        try {
            JsonNode n = JSON.readTree(f.toFile());
            return new String[]{n.path("gameVersion").asText(""), loaderName(n.path("baseModLoader").path("name").asText(""))};
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** The Minecraft version and loader of a Modrinth profile folder; "" for what its {@code profile.json} doesn't say. */
    private static String[] modrinth(Path dir) {
        try {
            if (Files.isRegularFile(dir.resolve("profile.json"))) {
                JsonNode m = JSON.readTree(dir.resolve("profile.json").toFile()).path("metadata");
                return new String[]{m.path("game_version").asText(""), loaderName(m.path("loader").asText(""))};
            }
        } catch (IOException | RuntimeException e) {
            // the mods tell
        }
        return new String[]{"", ""};
    }

    /** A Paper server: a {@code paper*.jar} in its folder. */
    private static boolean isPaper(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)).anyMatch(n -> n.startsWith("paper") && n.endsWith(".jar"));
        } catch (IOException e) {
            return false;
        }
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

    /** "1.21.1 Fabric", as the games list shows platforms; "26.2 Fabric?" when the version is a guess. */
    String platform() {
        String l = loader.equals("neoforge") ? "NeoForge" : loader.equals("fabric") ? "Fabric" : loader.equals("paper") ? "Paper" : "";
        return (minecraft + " " + l).strip() + (guessed ? "?" : "");
    }
}

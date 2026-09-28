package io.blockdesigner.blockcompanion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ModInstallerTest {
    @TempDir
    Path dir;

    private static ModInstaller.Release release(String tag, String... names) {
        return new ModInstaller.Release(tag, java.util.Arrays.stream(names).map(n -> new ModInstaller.Asset(n, "u:" + n, 1)).toList());
    }

    private static String name(java.util.Optional<ModInstaller.Asset> a) {
        return a.map(ModInstaller.Asset::name).orElse(null);
    }

    @Test
    void theNewestReleaseWinsNotTheLatestListed() throws Exception {
        String json = """
                [ {"tag_name":"0.10.0-rc1","prerelease":true,"assets":[]},
                  {"tag_name":"0.11.0","draft":true,"assets":[]},
                  {"tag_name":"0.2.0","assets":[{"name":"blockcompanion-paper-0.2.0.jar","browser_download_url":"u","size":1}]},
                  {"tag_name":"0.9.1","assets":[{"name":"blockcompanion-paper-0.9.1.jar","browser_download_url":"u","size":1}]},
                  {"tag_name":"0.10.0","assets":[{"name":"blockcompanion-paper-0.10.0.jar","browser_download_url":"u","size":1}]} ]""";
        ModInstaller.Release r = ModInstaller.newest(json.getBytes(StandardCharsets.UTF_8)).orElseThrow();
        assertThat(r.tag()).isEqualTo("0.10.0");
        assertThat(r.assets()).hasSize(1);
        assertThat(ModInstaller.newest("[{\"tag_name\":\"1.0.0\",\"prerelease\":true}]".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(ModInstaller.compareVersions("0.10.0", "0.9.2")).isPositive();
        assertThat(ModInstaller.compareVersions("v1.2", "1.2.0")).isZero();
    }

    @Test
    void theDigestComesWithTheAsset() throws Exception {
        String json = """
                [ {"tag_name":"0.4.0","assets":[
                    {"name":"blockcompanion-paper-0.4.0.jar","browser_download_url":"u","size":3,"digest":"sha256:abc"},
                    {"name":"blockcompanion-fabric-26.2-0.4.0.jar","browser_download_url":"u","size":3,"digest":null} ]} ]""";
        ModInstaller.Release r = ModInstaller.newest(json.getBytes(StandardCharsets.UTF_8)).orElseThrow();
        assertThat(r.assets()).extracting(ModInstaller.Asset::digest).containsExactly("sha256:abc", "");
    }

    @Test
    void aDownloadMustMatchItsSizeAndChecksum() throws Exception {
        Path f = Files.writeString(dir.resolve("blockcompanion-paper-0.4.0.jar.part"), "hello");
        String sha = "sha256:2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";   // SHA-256 of "hello"
        assertThat(ModInstaller.check(f, new ModInstaller.Asset("a.jar", "u", 5, sha))).isNull();
        assertThat(ModInstaller.check(f, new ModInstaller.Asset("a.jar", "u", 5, sha.toUpperCase().replace("SHA256", "sha256")))).isNull();
        assertThat(ModInstaller.check(f, new ModInstaller.Asset("a.jar", "u", 5, "sha256:" + "0".repeat(64))))
                .isEqualTo("its checksum doesn't match");
        assertThat(ModInstaller.check(f, new ModInstaller.Asset("a.jar", "u", 9, sha))).isEqualTo("5 bytes instead of 9");
        // What the release doesn't give isn't checked.
        assertThat(ModInstaller.check(f, new ModInstaller.Asset("a.jar", "u", 0, ""))).isNull();
        assertThat(ModInstaller.check(f, new ModInstaller.Asset("a.jar", "u", 5, "md5:whatever"))).isNull();
    }

    @Test
    void gitHubErrorsSaySomethingUseful() {
        long reset = java.time.LocalDateTime.of(2026, 9, 27, 14, 5).toEpochSecond(ZoneOffset.UTC);
        assertThat(ModInstaller.error(403, "0", Long.toString(reset), ZoneOffset.UTC)).isEqualTo("GitHub's rate limit is reached; try again at 14:05");
        assertThat(ModInstaller.error(429, "", "", ZoneOffset.UTC)).isEqualTo("GitHub's rate limit is reached; try again later");
        assertThat(ModInstaller.error(404, "", "", ZoneOffset.UTC)).isEqualTo("BlockCompanion's releases couldn't be found on GitHub");
        assertThat(ModInstaller.error(403, "12", "", ZoneOffset.UTC)).isEqualTo("GitHub answered 403");
    }

    @Test
    void theJarForTheVersionOrItsLine() {
        ModInstaller.Release r = release("0.2.0",
                "blockcompanion-fabric-1.21.1-0.2.0.jar", "blockcompanion-neoforge-1.21.1-0.2.0.jar",
                "blockcompanion-fabric-26.2-0.2.0.jar",
                "blockcompanion-fabric-26.3-0.2.0.jar", "blockcompanion-neoforge-26.3-0.2.0.jar", "blockcompanion-paper-0.2.0.jar");
        assertThat(name(ModInstaller.pick(r, "fabric", "26.2"))).isEqualTo("blockcompanion-fabric-26.2-0.2.0.jar");
        assertThat(name(ModInstaller.pick(r, "neoforge", "26.3.1"))).isEqualTo("blockcompanion-neoforge-26.3-0.2.0.jar");
        assertThat(name(ModInstaller.pick(r, "fabric", "26.2.1"))).isEqualTo("blockcompanion-fabric-26.2-0.2.0.jar");
        assertThat(name(ModInstaller.pick(r, "fabric", "1.21.4"))).isEqualTo("blockcompanion-fabric-1.21.1-0.2.0.jar");
        assertThat(name(ModInstaller.pick(r, "paper", "26.3"))).isEqualTo("blockcompanion-paper-0.2.0.jar");
        assertThat(ModInstaller.pick(r, "fabric", "1.21")).isEmpty();
        assertThat(ModInstaller.pick(r, "fabric", "26.1")).isEmpty();

        assertThat(ModInstaller.pick(r, "neoforge", "26.2")).isEmpty();
        assertThat(ModInstaller.missing(r, "neoforge", "26.2")).isEqualTo("BlockCompanion 0.2.0 has no NeoForge jar for 26.2; it has Fabric for 26.2");
        assertThat(ModInstaller.missing(r, "fabric", "26.1")).isEqualTo("BlockCompanion 0.2.0 has no Fabric jar for 26.1; it has Fabric for 1.21.1, 26.2, 26.3");
        assertThat(ModInstaller.loaders(r, "26.2")).containsExactly("fabric");
        assertThat(ModInstaller.loaders(r, "26.3.1")).containsExactly("fabric", "neoforge");
        assertThat(ModInstaller.loaders(r, "25.1")).isEmpty();
    }

    @Test
    void jarsAreToldByTheirWholeNameWhateverTheCase() {
        ModInstaller.Release r = release("0.2.0",
                "BlockCompanion-Fabric-26.2-0.2.0-sources.jar", "blockcompanion-fabric-26.2-0.2.0-dev.jar",
                "blockcompanion-fabric-26.2-0.2.0-dev-shadow.jar", "blockcompanion-common-26.2-0.2.0.jar", "blockcompanion-fabric-26.20-0.2.0.jar",
                "BlockCompanion-Fabric-26.2-0.2.0.JAR", "blockcompanion-paper-0.2.0-sources.jar", "readme.txt");
        assertThat(ModInstaller.jars(r)).extracting(j -> j.asset().name())
                .containsExactly("blockcompanion-fabric-26.20-0.2.0.jar", "BlockCompanion-Fabric-26.2-0.2.0.JAR");
        assertThat(name(ModInstaller.pick(r, "Fabric", "26.2"))).isEqualTo("BlockCompanion-Fabric-26.2-0.2.0.JAR");
        assertThat(ModInstaller.pick(r, "paper", "26.2")).isEmpty();
        assertThat(ModInstaller.jars(r).getLast().loader()).isEqualTo("fabric");
    }

    private Path zip(Path file, String entry, String text) throws IOException {
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream z = new ZipOutputStream(out)) {
            z.putNextEntry(new ZipEntry(entry));
            z.write(text.getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        return file;
    }

    @Test
    void otherBlockCompanionJarsGoByNameOrModId() throws Exception {
        Path mods = Files.createDirectories(dir.resolve("mods"));
        Files.writeString(mods.resolve("BlockCompanion-fabric-26.2-0.1.0.jar"), "old");
        zip(mods.resolve("renamed.jar"), "fabric.mod.json", "{ \"schemaVersion\": 1, \"id\": \"blockcompanion\" }");
        zip(mods.resolve("renamed-neo.jar"), "META-INF/neoforge.mods.toml", "[[mods]]\nmodId = \"blockcompanion\"\n");
        zip(mods.resolve("sodium.jar"), "fabric.mod.json", "{ \"id\": \"sodium\", \"depends\": { \"blockcompanion\": \"*\" } }");
        zip(mods.resolve("needs-it.jar"), "META-INF/neoforge.mods.toml", "[[mods]]\nmodId = \"other\"\n[[dependencies.other]]\nmodId = \"blockcompanions\"\n");
        Files.writeString(mods.resolve("blockcompanion-fabric-26.2-0.0.9.jar.disabled"), "older");
        Files.writeString(mods.resolve("blockcompanion-fabric-26.2-0.2.0.jar"), "new");
        ModInstaller.removeOthers(mods, mods.resolve("blockcompanion-fabric-26.2-0.2.0.jar"));
        try (var files = Files.list(mods)) {
            assertThat(files.map(p -> p.getFileName().toString()))
                    .containsExactlyInAnyOrder("blockcompanion-fabric-26.2-0.2.0.jar", "sodium.jar", "needs-it.jar");
        }

        Path plugins = Files.createDirectories(dir.resolve("server/plugins"));
        zip(plugins.resolve("BC.jar"), "plugin.yml", "name: BlockCompanion\nmain: io.blockcompanion.paper.Plugin\n");
        zip(plugins.resolve("Other.jar"), "plugin.yml", "name: BlockCompanionAddon\n");
        ModInstaller.removeOthers(plugins, null);
        try (var files = Files.list(plugins)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("Other.jar");
        }
        ModInstaller.removeOthers(dir.resolve("nowhere"), null);
    }

    @Test
    void aJarHeldOpenSaysWhichItIs() throws Exception {
        // Windows won't delete or rename a jar a game (or an older BlockDesigner) holds open with ZipFile.
        assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path mods = Files.createDirectories(dir.resolve("held/mods"));
        Path old = mods.resolve("blockcompanion-fabric-26.2-0.1.0.jar");
        zip(old, "fabric.mod.json", "{ \"id\": \"blockcompanion\" }");
        try (var held = new java.util.zip.ZipFile(old.toFile())) {
            assertThat(held.size()).isPositive();
            assertThatThrownBy(() -> ModInstaller.removeOthers(mods, null)).isInstanceOfSatisfying(ModInstaller.InUse.class,
                    e -> assertThat(e.jar).isEqualTo(old));
        }
        assertThat(old).exists();
    }

    @Test
    void aModsFolderPickedByHandGetsTheJar() {
        Path game = dir.resolve("Game");
        assertThat(ModInstaller.folderByHand(game, "fabric")).isEqualTo(game.resolve("mods"));
        assertThat(ModInstaller.folderByHand(game, "paper")).isEqualTo(game.resolve("plugins"));
        assertThat(ModInstaller.folderByHand(game.resolve("Mods"), "fabric")).isEqualTo(game.resolve("Mods"));
        assertThat(ModInstaller.folderByHand(game.resolve("plugins"), "paper")).isEqualTo(game.resolve("plugins"));
        assertThat(ModInstaller.gameFolder(game.resolve("mods"))).isEqualTo(game);
        assertThat(ModInstaller.gameFolder(game)).isEqualTo(game);
        assertThat(ModInstaller.isModsFolder(game.resolve("modsx"))).isFalse();
    }

    @Test
    void aFolderPickedByHandSaysWhatItRuns() throws Exception {
        Path inst = dir.resolve("PrismLauncher/instances/Pack");
        Files.createDirectories(inst.resolve("minecraft/mods"));
        Files.writeString(inst.resolve("mmc-pack.json"), """
                { "components": [ { "uid": "net.minecraft", "version": "26.2.1" }, { "uid": "net.fabricmc.fabric-loader" } ] }""");
        TextureGame t = TextureGame.at(inst.resolve("minecraft"), dir);
        assertThat(t.minecraft()).isEqualTo("26.2.1");
        assertThat(t.loader()).isEqualTo("fabric");

        Path server = Files.createDirectories(dir.resolve("server"));
        Files.writeString(server.resolve("paper-26.2-12.jar"), "");
        assertThat(TextureGame.at(server, dir).loader()).isEqualTo("paper");
        assertThat(TextureGame.at(Files.createDirectories(dir.resolve("empty")), dir)).extracting(TextureGame::minecraft, TextureGame::loader)
                .containsExactly("", "");
        assertThat(t.guessed()).isFalse();
    }
}

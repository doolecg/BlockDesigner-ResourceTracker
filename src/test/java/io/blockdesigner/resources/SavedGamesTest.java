package io.blockdesigner.resources;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SavedGamesTest {
    private static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");

    @TempDir
    Path dir;

    private static GameInstance game(String id, String name, Instant updated, boolean closed) {
        return new GameInstance(id, "client", name, "World", "1.21.1", "fabric", "0.2.0", 25600, "t", List.of("C:\\packs\\a.zip"), List.of(), updated,
                closed, "C:\\games\\" + name);
    }

    @Test
    void disconnectedGamesLeaveUnlessSaved() {
        GameInstance running = game("1", "Alex", NOW.minusSeconds(3), false);
        GameInstance closed = game("2", "Steve", NOW.minusSeconds(60), true);
        GameInstance stale = game("3", "Kai", NOW.minusSeconds(120), false);
        List<GameInstance> shown = GameLinks.visible(List.of(running, closed, stale), Map.of(), g -> false, NOW);
        assertThat(shown).containsExactly(running);

        shown = GameLinks.visible(List.of(running, closed, stale), Map.of(GameLinks.key(closed), closed), g -> false, NOW);
        assertThat(shown).containsExactly(running, closed);
    }

    @Test
    void stillConnectedGamesStay() {
        GameInstance stale = game("3", "Kai", NOW.minusSeconds(120), false);
        assertThat(GameLinks.visible(List.of(stale), Map.of(), g -> true, NOW)).containsExactly(stale);
    }

    @Test
    void savedGamesShowWithoutTheirFileAndOncePerGame() {
        GameInstance old = game("old", "Steve", NOW.minusSeconds(3600), true);
        GameInstance again = game("new", "Steve", NOW.minusSeconds(2), false);
        GameInstance gone = game("saved:x", "Kai", NOW.minusSeconds(86400 * 9), true);
        Map<String, GameInstance> saved = Map.of(GameLinks.key(old), old, GameLinks.key(gone), gone);
        assertThat(GameLinks.visible(List.of(old, again), saved, g -> false, NOW)).containsExactly(again, gone);
        assertThat(GameLinks.visible(List.of(old), saved, g -> false, NOW)).containsExactly(old, gone);
    }

    @Test
    void savedGamesAreKept() {
        Prefs p = new Prefs(dir);
        GameInstance g = game("1", "Steve", NOW, false);
        p.saved.put(GameLinks.key(g), g);
        p.save();

        GameInstance back = new Prefs(dir).saved.get(GameLinks.key(g));
        assertThat(back).isNotNull();
        assertThat(back.closed()).isTrue();
        assertThat(back.label()).isEqualTo("Steve · World");
        assertThat(back.platform()).isEqualTo("1.21.1 Fabric");
        assertThat(back.clientPacks()).containsExactly("C:\\packs\\a.zip");
        assertThat(back.updated()).isEqualTo(NOW);
        assertThat(GameLinks.key(back)).isEqualTo(GameLinks.key(g));
    }

    @Test
    void texturesGameIsThePrismInstance() throws Exception {
        Path appData = dir.resolve("appdata"), home = dir.resolve("home");
        Path inst = appData.resolve("PrismLauncher/instances/Build Pack");
        Files.createDirectories(inst.resolve("minecraft"));
        Files.writeString(inst.resolve("mmc-pack.json"), """
                { "components": [ { "uid": "net.minecraft", "version": "1.21.1" }, { "uid": "net.neoforged", "version": "21.1.77" } ] }""");
        var settings = new ObjectMapper().readTree("""
                { "gameJar": "C:/libs/minecraft-1.21.1-client.jar", "instanceName": "Build Pack" }""");
        TextureGame t = TextureGame.of(settings, List.of(), appData, home).orElseThrow();
        assertThat(t.label()).isEqualTo("Build Pack");
        assertThat(t.minecraft()).isEqualTo("1.21.1");
        assertThat(t.loader()).isEqualTo("neoforge");
        assertThat(t.gameDir()).isEqualTo(inst.resolve("minecraft"));
        assertThat(t.platform()).isEqualTo("1.21.1 NeoForge");
        assertThat(t.guessed()).isFalse();
    }

    @Test
    void texturesGameFromItsPacksFolderOrTheLauncher() throws Exception {
        Path appData = dir.resolve("appdata"), home = dir.resolve("home");
        var settings = new ObjectMapper().readTree("""
                { "gameJar": "%s" }""".formatted(appData.resolve(".minecraft/versions/26.2/26.2.jar").toString().replace("\\", "/")));
        Path game = dir.resolve("MyGame");
        TextureGame fromPack = TextureGame.of(settings, List.of(game.resolve("resourcepacks/Faithful.zip")), appData, home).orElseThrow();
        assertThat(fromPack.gameDir()).isEqualTo(game);
        assertThat(fromPack.label()).isEqualTo("MyGame");
        assertThat(fromPack.minecraft()).isEqualTo("26.2");
        assertThat(fromPack.loader()).isEmpty();
        assertThat(fromPack.guessed()).isTrue();
        assertThat(fromPack.platform()).isEqualTo("26.2?");

        TextureGame vanilla = TextureGame.of(settings, List.of(), appData, home).orElseThrow();
        assertThat(vanilla.gameDir()).isEqualTo(appData.resolve(".minecraft"));
        assertThat(vanilla.label()).isEqualTo("Minecraft Launcher");
        assertThat(vanilla.guessed()).isTrue();
    }

    @Test
    void noJarNoTexturesGame() throws Exception {
        assertThat(TextureGame.of(new ObjectMapper().readTree("{}"), List.of(), dir, dir)).isEmpty();
        assertThat(TextureGame.loaderName("fabric-0.16.5-1.21.1")).isEqualTo("fabric");
        assertThat(TextureGame.loaderName("neoforge-21.1.77")).isEqualTo("neoforge");
        assertThat(TextureGame.loaderName("forge-47.2.0")).isEmpty();
    }
}

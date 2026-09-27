package io.blockdesigner.resources;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** The live link to BlockCompanion: instance files, a connection, installing the mod, and chests in the counts. */
class LiveLinkTest {
    @TempDir
    Path dir;

    static String instance(String id, String kind, Instant updated, boolean closed, int port) {
        return """
                {"format":1,"id":"%s","kind":"%s","name":"Steve","world":"My World","minecraft":"1.21.1","loader":"neoforge",
                 "modVersion":"0.2.0","pid":1,"port":%d,"token":"abc","clientPacks":["C:/packs/a.zip"],"serverPacks":["https://x/p.zip"],
                 "started":"2026-09-27T10:00:00Z","updated":"%s","closed":%s,"gameDir":"C:/games/mc"}
                """.formatted(id, kind, port, updated, closed);
    }

    @Test
    void instanceFilesAreReadActiveOrNot() throws Exception {
        Instant now = Instant.parse("2026-09-27T12:00:00Z");
        Files.writeString(dir.resolve("a.json"), instance("a", "client", now.minusSeconds(3), false, 1));
        Files.writeString(dir.resolve("b.json"), instance("b", "server", now.minusSeconds(60), false, 2));
        Files.writeString(dir.resolve("c.json"), instance("c", "client", now.minusSeconds(3), true, 3));
        Files.writeString(dir.resolve("old.json"), instance("old", "client", now.minusSeconds(5 * 86400), true, 4));
        Files.writeString(dir.resolve("broken.json"), "{\"format\":1,");
        Files.writeString(dir.resolve("a.json.tmp"), "junk");
        List<GameInstance> list = GameInstance.scan(dir, now);
        assertThat(list).extracting(GameInstance::id).containsExactlyInAnyOrder("a", "b", "c");
        GameInstance a = list.stream().filter(g -> g.id().equals("a")).findFirst().orElseThrow();
        assertThat(a.active(now)).isTrue();
        assertThat(a.platform()).isEqualTo("1.21.1 NeoForge");
        assertThat(a.label()).isEqualTo("Steve · My World");
        assertThat(a.gameDir()).isEqualTo("C:/games/mc");
        assertThat(list.stream().filter(g -> !g.id().equals("a")).noneMatch(g -> g.active(now))).isTrue();
        assertThat(list.stream().filter(g -> g.id().equals("b")).findFirst().orElseThrow().server()).isTrue();
    }

    @Test
    void connectionSaysHelloAndPassesMessagesOn() throws Exception {
        ObjectMapper json = new ObjectMapper();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            BlockingQueue<String> gotHello = new LinkedBlockingQueue<>();
            Thread game = new Thread(() -> {
                try (Socket s = server.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                    gotHello.add(in.readLine());
                    OutputStream out = s.getOutputStream();
                    out.write("{\"type\":\"welcome\",\"protocol\":1}\n{\"type\":\"status\",\"chests\":{\"count\":2,\"items\":{\"minecraft:stone\":64}}}\n{\"type\":\"grab\"}\n"
                            .getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    gotHello.add(in.readLine());
                } catch (Exception e) {
                    gotHello.add("error " + e);
                }
            });
            game.start();
            GameInstance g = GameInstance.parse(instance("x", "client", Instant.now(), false, server.getLocalPort()).getBytes(StandardCharsets.UTF_8));
            BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
            GameConnection c = new GameConnection(g, "1.2.0", new GameConnection.Listener() {
                public void message(GameConnection c, JsonNode m) {
                    messages.add(m);
                }

                public void stateChanged(GameConnection c) {
                }
            }, Runnable::run);
            c.start();
            JsonNode hello = json.readTree(gotHello.poll(5, TimeUnit.SECONDS));
            assertThat(hello.path("type").asText()).isEqualTo("hello");
            assertThat(hello.path("token").asText()).isEqualTo("abc");
            assertThat(hello.path("app").asText()).isEqualTo(GameConnection.APP);
            assertThat(messages.poll(5, TimeUnit.SECONDS).path("type").asText()).isEqualTo("welcome");
            assertThat(c.state()).isEqualTo(GameConnection.State.CONNECTED);
            assertThat(messages.poll(5, TimeUnit.SECONDS).path("chests").path("items").path("minecraft:stone").asLong()).isEqualTo(64);
            assertThat(messages.poll(5, TimeUnit.SECONDS).path("type").asText()).isEqualTo("grab");
            assertThat(c.send(c.newMessage("refresh"))).isTrue();
            assertThat(gotHello.poll(5, TimeUnit.SECONDS)).contains("refresh");
            c.close();
        }
    }

    @Test
    void theRightJarIsPicked() throws Exception {
        ModInstaller.Release r = ModInstaller.parse("""
                {"tag_name":"0.2.0","assets":[
                 {"name":"blockcompanion-fabric-1.21.1-0.2.0.jar","browser_download_url":"u1","size":1},
                 {"name":"blockcompanion-fabric-1.21.1-0.2.0-sources.jar","browser_download_url":"u2","size":1},
                 {"name":"blockcompanion-neoforge-26.3-0.2.0.jar","browser_download_url":"u3","size":1},
                 {"name":"blockcompanion-paper-0.2.0.jar","browser_download_url":"u4","size":1}]}
                """.getBytes(StandardCharsets.UTF_8));
        assertThat(r.tag()).isEqualTo("0.2.0");
        assertThat(ModInstaller.pick(r, "fabric", "1.21.1")).get().extracting(ModInstaller.Asset::url).isEqualTo("u1");
        assertThat(ModInstaller.pick(r, "NeoForge", "26.3")).get().extracting(ModInstaller.Asset::url).isEqualTo("u3");
        assertThat(ModInstaller.pick(r, "paper", "1.21.1")).get().extracting(ModInstaller.Asset::url).isEqualTo("u4");
        assertThat(ModInstaller.pick(r, "fabric", "26.2")).isEmpty();
        assertThat(ModInstaller.folder(dir, "paper")).isEqualTo(dir.resolve("plugins"));
        assertThat(ModInstaller.folder(dir, "fabric")).isEqualTo(dir.resolve("mods"));

        Path mods = Files.createDirectories(dir.resolve("mods"));
        Files.writeString(mods.resolve("blockcompanion-fabric-1.21.1-0.1.0.jar"), "old");
        Files.writeString(mods.resolve("blockcompanion-fabric-1.21.1-0.2.0.jar"), "new");
        Files.writeString(mods.resolve("sodium.jar"), "other");
        ModInstaller.removeOthers(mods, mods.resolve("blockcompanion-fabric-1.21.1-0.2.0.jar"));
        try (var files = Files.list(mods)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactlyInAnyOrder("blockcompanion-fabric-1.21.1-0.2.0.jar", "sodium.jar");
        }
    }

    @Test
    void chestsCountAsHave() {
        assertThat(Tracker.left(100, 30, 20 + 40)).isEqualTo(10);
        assertThat(Tracker.rest(100, 30, 40)).isEqualTo(30);
        assertThat(Tracker.rest(100, 90, 40)).isZero();
        GameInstance g = new GameInstance("x", "client", "", "", "", "", "", 0, "", List.of(dir.resolve("missing.zip").toString(), dir.toString()),
                List.of("https://example.com/pack.zip"), Instant.now(), false, "");
        assertThat(GameLinks.packs(g)).containsExactly(dir);
    }
}

package io.blockdesigner.resources;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GameProgressTest {
    static final String GOOD = """
            {
              "format": 1,
              "schematic": "Watchtower.bdproj",
              "sha256": "ab12cd34ef56ab12cd34ef56ab12cd34ef56ab12cd34ef56ab12cd34ef56abcd",
              "projectName": "Watchtower",
              "world": "bctest",
              "updated": "2026-09-27T14:03:12Z",
              "total": 14445, "correct": 5120, "wrong": 12, "missing": 9313,
              "items": { "minecraft:oak_planks": { "needed": 640, "placed": 210 },
                         "minecraft:torch": { "needed": 8, "placed": 8 } }
            }
            """;

    private static GameProgress parse(String json) throws IOException {
        return GameProgress.parse(Path.of("Watchtower-ab12cd34ef56.json"), json.getBytes(StandardCharsets.UTF_8), Instant.EPOCH);
    }

    @Test
    void readsAGoodFile() throws IOException {
        GameProgress p = parse(GOOD);
        assertThat(p.projectName()).isEqualTo("Watchtower");
        assertThat(p.schematicFileName()).isEqualTo("Watchtower.bdproj");
        assertThat(p.world()).isEqualTo("bctest");
        assertThat(p.updated()).isEqualTo(Instant.parse("2026-09-27T14:03:12Z"));
        assertThat(p.total()).isEqualTo(14445);
        assertThat(p.correct()).isEqualTo(5120);
        assertThat(p.wrong()).isEqualTo(12);
        assertThat(p.missing()).isEqualTo(9313);
        assertThat(p.items()).containsEntry("minecraft:oak_planks", new GameProgress.Item(640, 210));
        assertThat(p.placed("minecraft:oak_planks")).isEqualTo(210);
        assertThat(p.placed("minecraft:stone")).isZero();
        assertThat(p.label()).isEqualTo("Watchtower · bctest");
    }

    @Test
    void readsFromDisk(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("Watchtower-ab12cd34ef56.json");
        Files.writeString(f, GOOD);
        assertThat(GameProgress.read(f).file()).isEqualTo(f);
    }

    @Test
    void missingOptionalFieldsFallBack() throws IOException {
        GameProgress p = parse("{\"format\":1,\"items\":{\"minecraft:stone\":{\"needed\":5,\"placed\":-3}},\"updated\":\"yesterday\"}");
        assertThat(p.projectName()).isNull();
        assertThat(p.updated()).isEqualTo(Instant.EPOCH);   // the fallback time
        assertThat(p.placed("minecraft:stone")).isZero();   // negative clamps
        assertThat(p.total()).isZero();
        assertThat(p.label()).isEqualTo("Watchtower-ab12cd34ef56.json");
    }

    @Test
    void refusesAPartlyWrittenFile() {
        assertThatThrownBy(() -> parse(GOOD.substring(0, GOOD.length() / 2))).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> parse("")).isInstanceOf(IOException.class);
    }

    @Test
    void refusesACorruptFile() {
        assertThatThrownBy(() -> parse("not json at all")).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> parse("[1, 2, 3]")).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> parse(GOOD + "}")).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> parse("{\"format\":1}")).isInstanceOf(IOException.class).hasMessageContaining("no items");
    }

    @Test
    void refusesAnUnknownFormat() {
        assertThatThrownBy(() -> parse(GOOD.replace("\"format\": 1", "\"format\": 2"))).isInstanceOf(IOException.class)
                .hasMessageContaining("format 2");
        assertThatThrownBy(() -> parse(GOOD.replace("\"format\": 1,", ""))).isInstanceOf(IOException.class)
                .hasMessageContaining("no format");
    }

    @Test
    void statusAndStaleness() throws IOException {
        GameProgress p = parse(GOOD);
        Instant t = p.updated();
        assertThat(p.isStale(t.plusSeconds(5))).isFalse();
        assertThat(p.status(t.plusSeconds(5))).isEqualTo("In game: 35% built · 9,313 blocks left · 12 wrong · updated 5 s ago");
        assertThat(p.isStale(t.plus(GameProgress.STALE_AFTER).minusSeconds(1))).isFalse();
        assertThat(p.isStale(t.plus(GameProgress.STALE_AFTER))).isTrue();
        assertThat(p.status(t.plus(Duration.ofMinutes(10)))).endsWith(" · not seen for 10 min");
        assertThat(GameProgress.ago(Duration.ofHours(3))).isEqualTo("3 h");
        assertThat(GameProgress.ago(Duration.ofDays(1))).isEqualTo("1 day");
        assertThat(GameProgress.ago(Duration.ofSeconds(-4))).isEqualTo("0 s");   // clocks slightly apart
    }
}

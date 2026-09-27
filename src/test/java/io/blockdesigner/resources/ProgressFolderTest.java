package io.blockdesigner.resources;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProgressFolderTest {
    private static String json(String project, String schematic, String updated, long placed) {
        return """
                {"format":1,"schematic":"%s","projectName":"%s","world":"w","updated":"%s",
                 "total":10,"correct":5,"wrong":0,"missing":5,"items":{"minecraft:stone":{"needed":10,"placed":%d}}}
                """.formatted(schematic, project, updated, placed);
    }

    private static GameProgress build(String file, String project, String schematic, String updated) {
        return new GameProgress(Path.of(file), schematic, null, project, "w", Instant.parse(updated), 0, 0, 0, 0, Map.of());
    }

    @Test
    void missingFolder(@TempDir Path dir) {
        ProgressFolder.Snapshot s = new ProgressFolder(dir.resolve("nope")).scan();
        assertThat(s.folderExists()).isFalse();
        assertThat(s.builds()).isEmpty();
    }

    @Test
    void listsBuildsNewestFirstAndSkipsOthers(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("A-111111111111.json"), json("A", "A.bdproj", "2026-09-27T10:00:00Z", 1));
        Files.writeString(dir.resolve("B-222222222222.json"), json("B", "B.schem", "2026-09-27T12:00:00Z", 2));
        Files.writeString(dir.resolve("C-333333333333.json.tmp"), "{");   // a write in progress
        Files.writeString(dir.resolve("D-444444444444.json"), "{\"format\":");   // broken, never good
        ProgressFolder.Snapshot s = new ProgressFolder(dir).scan();
        assertThat(s.folderExists()).isTrue();
        assertThat(s.builds()).extracting(GameProgress::projectName).containsExactly("B", "A");
        assertThat(s.byFileName("a-111111111111.JSON")).isPresent();
    }

    @Test
    void keepsTheLastGoodDataWhenAFileBreaks(@TempDir Path dir) throws IOException {
        ProgressFolder folder = new ProgressFolder(dir);
        Path f = dir.resolve("A-111111111111.json");
        Files.writeString(f, json("A", "A.bdproj", "2026-09-27T10:00:00Z", 3));
        assertThat(folder.scan().builds().getFirst().placed("minecraft:stone")).isEqualTo(3);

        Files.writeString(f, "{\"format\":1,\"items\":{\"minecraft:st");   // partly written
        Files.setLastModifiedTime(f, FileTime.from(Instant.now().plusSeconds(10)));
        ProgressFolder.Snapshot broken = folder.scan();
        assertThat(broken.builds()).hasSize(1);
        assertThat(broken.builds().getFirst().placed("minecraft:stone")).isEqualTo(3);

        Files.writeString(f, json("A", "A.bdproj", "2026-09-27T10:00:02Z", 4));
        Files.setLastModifiedTime(f, FileTime.from(Instant.now().plusSeconds(20)));
        assertThat(folder.scan().builds().getFirst().placed("minecraft:stone")).isEqualTo(4);

        Files.writeString(f, json("A", "A.bdproj", "2026-09-27T10:00:04Z", 5).replace("\"format\":1", "\"format\":9"));
        Files.setLastModifiedTime(f, FileTime.from(Instant.now().plusSeconds(30)));
        assertThat(folder.scan().builds().getFirst().placed("minecraft:stone")).isEqualTo(4);   // newer format: keep

        Files.delete(f);
        assertThat(folder.scan().builds()).isEmpty();
    }

    @Test
    void matchesByProjectNameOrFileName() {
        List<GameProgress> builds = List.of(
                build("Watchtower-aaaaaaaaaaaa.json", "Watchtower", "Watchtower.bdproj", "2026-09-27T10:00:00Z"),
                build("Watchtower-bbbbbbbbbbbb.json", "Watchtower", "Watchtower.bdproj", "2026-09-27T12:00:00Z"),
                build("Castle-cccccccccccc.json", "Keep", "C:\\schematics\\castle.bdproj", "2026-09-27T13:00:00Z"),
                build("Barn-dddddddddddd.json", null, "barn.schem", "2026-09-27T14:00:00Z"));
        // Several match: the most recently updated.
        assertThat(ProgressFolder.match(builds, "Watchtower", null)).map(p -> p.file().toString()).contains("Watchtower-bbbbbbbbbbbb.json");
        // Case and spaces don't matter.
        assertThat(ProgressFolder.match(builds, " watchtower ", null)).isPresent();
        // The schematic's file name (without its folder) against the project file's name.
        assertThat(ProgressFolder.match(builds, "Castle", "Castle.bdproj")).map(GameProgress::projectName).contains("Keep");
        assertThat(ProgressFolder.match(builds, null, "barn.schem")).map(p -> p.file().toString()).contains("Barn-dddddddddddd.json");
        // Nothing matching, or nothing to match by.
        assertThat(ProgressFolder.match(builds, "Village", "Village.bdproj")).isEmpty();
        assertThat(ProgressFolder.match(builds, null, null)).isEmpty();
        assertThat(ProgressFolder.match(List.of(), "Watchtower", "Watchtower.bdproj")).isEmpty();
    }
}

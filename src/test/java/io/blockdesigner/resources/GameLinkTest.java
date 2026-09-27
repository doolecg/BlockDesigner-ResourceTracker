package io.blockdesigner.resources;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** The left/done arithmetic, the saved game link, and the project name used for matching. */
class GameLinkTest {
    @Test
    void leftIsNeededMinusPlacedMinusGathered() {
        assertThat(Tracker.left(640, 0, 0)).isEqualTo(640);
        assertThat(Tracker.left(640, 210, 0)).isEqualTo(430);
        assertThat(Tracker.left(640, 210, 100)).isEqualTo(330);
        assertThat(Tracker.left(640, 640, 0)).isZero();      // all placed: done
        assertThat(Tracker.left(640, 700, 0)).isZero();      // more placed than the tracker counts
        assertThat(Tracker.left(640, 500, 500)).isZero();    // never below zero
        assertThat(Tracker.left(640, -5, -5)).isEqualTo(640);
        // Not linked (placed 0) works as before: needed - gathered.
        assertThat(Tracker.left(640, 0, 64)).isEqualTo(576);
    }

    @Test
    void coveredAndRest() {
        assertThat(Tracker.covered(640, 210, 100)).isEqualTo(310);
        assertThat(Tracker.covered(640, 600, 100)).isEqualTo(640);   // capped for the progress bar
        assertThat(Tracker.rest(640, 210)).isEqualTo(430);           // "gathered all of it" when 210 are placed
        assertThat(Tracker.rest(640, 0)).isEqualTo(640);
        assertThat(Tracker.rest(640, 900)).isZero();
    }

    @Test
    void linkIsSavedPerProject(@TempDir Path data) throws IOException {
        Optional<Path> project = Optional.of(data.resolve("Watchtower.bdproj"));
        Gathered g = new Gathered(data);
        g.open(project);
        assertThat(g.link()).isEqualTo(Gathered.Link.AUTO);

        g.setLink(Gathered.Link.file("Watchtower-aaaaaaaaaaaa.json"));
        g.save(project);   // no counts, but a link: kept
        Gathered again = new Gathered(data);
        again.open(project);
        assertThat(again.link()).isEqualTo(Gathered.Link.file("Watchtower-aaaaaaaaaaaa.json"));
        assertThat(again.isEmpty()).isTrue();

        again.setLink(Gathered.Link.OFF);
        again.set("minecraft:stone", 5);
        again.save(project);
        Gathered third = new Gathered(data);
        third.open(project);
        assertThat(third.link()).isEqualTo(Gathered.Link.OFF);
        assertThat(third.get("minecraft:stone")).isEqualTo(5);

        // Another project starts automatic.
        third.open(Optional.of(data.resolve("Other.bdproj")));
        assertThat(third.link()).isEqualTo(Gathered.Link.AUTO);

        // Back to automatic with no counts: the file goes.
        Gathered fourth = new Gathered(data);
        fourth.open(project);
        fourth.setLink(Gathered.Link.AUTO);
        fourth.clear();
        fourth.save(project);
        try (var files = Files.list(data.resolve("projects"))) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void oldFilesWithoutALinkAreAutomatic(@TempDir Path data) throws IOException {
        Optional<Path> project = Optional.of(data.resolve("Old.bdproj"));
        Gathered g = new Gathered(data);
        g.open(project);
        g.set("minecraft:stone", 3);
        g.save(project);
        String saved;
        try (var files = Files.list(data.resolve("projects"))) {
            saved = Files.readString(files.findFirst().orElseThrow());
        }
        assertThat(saved).doesNotContain("gameProgress");
        Gathered again = new Gathered(data);
        again.open(project);
        assertThat(again.link()).isEqualTo(Gathered.Link.AUTO);
    }

    @Test
    void projectNameComesFromTheProjectFile(@TempDir Path dir) throws IOException {
        Path bdproj = dir.resolve("tower-v2.bdproj");
        try (OutputStream out = Files.newOutputStream(bdproj); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("project.json"));
            zip.write("{\"format\":2,\"name\":\"Watchtower\",\"layers\":[]}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        assertThat(Tracker.projectName(bdproj)).isEqualTo("Watchtower");
        Path broken = dir.resolve("Broken.bdproj");
        Files.writeString(broken, "not a zip");
        assertThat(Tracker.projectName(broken)).isEqualTo("Broken");
        assertThat(Tracker.projectName(dir.resolve("Barn.schem"))).isEqualTo("Barn");
    }
}

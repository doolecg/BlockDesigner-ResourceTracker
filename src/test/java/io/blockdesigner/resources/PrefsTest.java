package io.blockdesigner.resources;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PrefsTest {
    @TempDir
    Path dir;

    @Test
    void defaultsWithoutAFile() {
        Prefs p = new Prefs(dir);
        assertThat(p.live).isFalse();
        assertThat(p.mobs).isFalse();
        assertThat(p.settingsMigrated).isFalse();
        assertThat(p.excluded).isEmpty();
        assertThat(Prefs.constant(Tally.Scope.class, p.scope, Tally.Scope.VISIBLE)).isEqualTo(Tally.Scope.VISIBLE);
    }

    @Test
    void savedAndReadBack() {
        Prefs p = new Prefs(dir);
        p.scope = Tally.Scope.ACTIVE.name();
        p.sort = "NAME";
        p.hideDone = true;
        p.live = true;
        p.settingsMigrated = true;
        p.excluded.add("client|Steve|C:\\game");
        p.save();

        Prefs q = new Prefs(dir);
        assertThat(Prefs.constant(Tally.Scope.class, q.scope, Tally.Scope.VISIBLE)).isEqualTo(Tally.Scope.ACTIVE);
        assertThat(q.sort).isEqualTo("NAME");
        assertThat(q.hideDone).isTrue();
        assertThat(q.live).isTrue();
        assertThat(q.settingsMigrated).isTrue();
        assertThat(q.excluded).containsExactly("client|Steve|C:\\game");
    }

    @Test
    void filesFrom120StillRead() throws Exception {
        // 1.2.0 kept the page shown and the Mobs tick here; the page is now BlockDesigner's, Mobs is in Settings.
        Files.writeString(dir.resolve("settings.json"), """
                { "page": "game", "scope": "SELECTION", "mobs": true, "sort": "NAME", "hideDone": true,
                  "gameLink": { "live": true, "excluded": ["a"] } }""");
        Prefs p = new Prefs(dir);
        assertThat(p.scope).isEqualTo("SELECTION");
        assertThat(p.mobs).isTrue();
        assertThat(p.live).isTrue();
        assertThat(p.excluded).containsExactly("a");
        p.save();
        assertThat(Files.readString(dir.resolve("settings.json"))).doesNotContain("\"page\"");
    }

    @Test
    void theOldMobsTickMovesToSettingsOnce() throws Exception {
        Files.writeString(dir.resolve("settings.json"), "{ \"mobs\": true }");
        Prefs p = new Prefs(dir);
        assertThat(TrackerSession.migrateMobs(p)).isTrue();
        assertThat(TrackerSession.migrateMobs(p)).as("only once").isFalse();
        assertThat(TrackerSession.migrateMobs(new Prefs(dir))).as("remembered in the file").isFalse();

        Path other = Files.createDirectory(dir.resolve("off"));
        assertThat(TrackerSession.migrateMobs(new Prefs(other))).as("nothing to carry over").isFalse();
    }

    @Test
    void unreadableFileGivesDefaults() throws Exception {
        Files.writeString(dir.resolve("settings.json"), "{ not json");
        Prefs p = new Prefs(dir);
        assertThat(p.live).isFalse();
        assertThat(Prefs.constant(Tally.Scope.class, "NOPE", Tally.Scope.VISIBLE)).isEqualTo(Tally.Scope.VISIBLE);
    }
}

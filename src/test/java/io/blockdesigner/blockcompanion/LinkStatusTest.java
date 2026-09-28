package io.blockdesigner.blockcompanion;

import io.blockdesigner.plugin.ui.Tone;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** How the link stands, as the BlockCompanion page's status line and the dot on its page button show it. */
class LinkStatusTest {
    @Test
    void connected() {
        LinkStatus s = LinkStatus.of(2, 2, 3, 1);
        assertThat(s.tone()).isEqualTo(Tone.SUCCESS);
        assertThat(s.title()).isEqualTo("Connected to 2 games");
        assertThat(s.detail()).isEqualTo("2 active, 1 disconnected · 1 linked chest");
        assertThat(LinkStatus.of(1, 1, 1, 0).title()).isEqualTo("Connected to 1 game");
    }

    @Test
    void connecting() {
        LinkStatus s = LinkStatus.of(0, 1, 1, 0);
        assertThat(s.tone()).isEqualTo(Tone.WARNING);
        assertThat(s.title()).isEqualTo("Connecting to 1 game…");
    }

    @Test
    void waitingOrNoGame() {
        assertThat(LinkStatus.of(0, 0, 2, 0).tone()).isEqualTo(Tone.NEUTRAL);
        assertThat(LinkStatus.of(0, 0, 2, 0).title()).isEqualTo("Waiting for a game");
        assertThat(LinkStatus.of(0, 0, 0, 0).title()).isEqualTo("No game found");
        assertThat(LinkStatus.of(0, 0, 0, 0).tone()).isEqualTo(Tone.NEUTRAL);
    }
}

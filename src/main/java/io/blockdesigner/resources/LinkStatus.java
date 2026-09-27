package io.blockdesigner.resources;

import io.blockdesigner.plugin.ui.Tone;

import java.time.Instant;
import java.util.List;

/**
 * How the live link to BlockCompanion games stands, for the BlockCompanion page's status line and the dot on its page
 * button: connected (green), connecting (yellow), or waiting for a game / none found (grey).
 */
record LinkStatus(Tone tone, String title, String detail) {
    static LinkStatus of(GameLinks links) {
        Instant now = Instant.now();
        List<GameInstance> all = links.instances();
        long connected = all.stream().filter(links::connected).count();
        long active = all.stream().filter(g -> g.active(now)).count();
        return of(connected, active, all.size(), links.chestCount());
    }

    /** From the counts: games connected, active (running), known at all, and linked chests. */
    static LinkStatus of(long connected, long active, int total, int chests) {
        String counts = active + " active, " + (total - active) + " disconnected"
                + (chests > 0 ? " · " + chests + (chests == 1 ? " linked chest" : " linked chests") : "");
        if (connected > 0) return new LinkStatus(Tone.SUCCESS, "Connected to " + connected + (connected == 1 ? " game" : " games"), counts);
        if (active > 0) return new LinkStatus(Tone.WARNING, "Connecting to " + active + (active == 1 ? " game…" : " games…"), counts);
        if (total > 0) return new LinkStatus(Tone.NEUTRAL, "Waiting for a game",
                "No BlockCompanion game is running. Start Minecraft with it and it connects by itself.");
        return new LinkStatus(Tone.NEUTRAL, "No game found", "Install the BlockCompanion mod, then start Minecraft.");
    }
}

package io.blockdesigner.resources;

import io.blockdesigner.plugin.Options;
import io.blockdesigner.plugin.PluginContext;
import io.blockdesigner.plugin.SceneEvent;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Everything the plugin keeps while it is on, shared by its two pages and its Plugins menu actions: the count
 * ({@link Tracker}), the live link ({@link GameLinks}), the saved page state ({@link Prefs}), and a look at the games
 * every 2 seconds (the instance folder and BlockCompanion's progress folder), from the start, so a game's Grab button
 * works and the BlockCompanion page's dot is right before either page is opened.
 */
final class TrackerSession {
    /** The BlockCompanion page's id (for its status dot and for links to it). */
    static final String GAME_PAGE = "blockcompanion";

    /** The plugin's page in the Settings window. */
    static final Options SETTINGS = Options.builder()
            .group("Counting")
            .toggle("mobs", "Count mobs as their spawn eggs", false)
            .help("Paintings, frames, armor stands, boats and minecarts always count.")
            .build();

    /** What the pages redraw on. */
    interface Listener {
        /** What is counted may have changed (the game's progress, chests, a setting); {@code recount}: the build too. */
        default void countChanged(boolean recount) {
        }

        /** Nothing changed, but "placed 5 s ago" moves on. */
        default void tick() {
        }
    }

    final PluginContext ctx;
    final Prefs prefs;
    final Tracker tracker;
    final GameLinks links;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final GameLinks.Listener linksChanged = this::linksChanged;
    private ScheduledExecutorService watcher;
    private volatile boolean closed;

    TrackerSession(PluginContext ctx) {
        this.ctx = ctx;
        prefs = new Prefs(ctx.dataFolder());
        tracker = new Tracker(ctx);
        tracker.scope = Prefs.constant(Tally.Scope.class, prefs.scope, Tally.Scope.VISIBLE);
        links = new GameLinks(ctx, ctx.info().version(), prefs);
        // Follow the open project from the start, before a page is first shown.
        ctx.on(SceneEvent.ProjectOpened.class, e -> {
            tracker.projectOpened(e.file());
            links.projectOpened(e.file(), tracker.projectName());
            fire(true);
        });
        links.addListener(linksChanged);
    }

    /** Registers the Settings page (applying its saved values) and moves an older "Mobs" tick there once. */
    void registerSettings() {
        ctx.registerSettings(SETTINGS, v -> setMobs(v.toggle("mobs")));
        if (migrateMobs(prefs)) ctx.updateSettings(v -> v.with("mobs", true));
    }

    /**
     * Before 1.3.0 "Mobs" was a tick on the Materials page, kept in the plugin's own settings file; now it is on the
     * plugin's Settings page. True (once) when the old tick was on and should be carried over.
     */
    static boolean migrateMobs(Prefs prefs) {
        if (prefs.settingsMigrated) return false;
        prefs.settingsMigrated = true;
        prefs.save();
        return prefs.mobs;
    }

    /** Starts looking at the games every 2 seconds (the first look straight away). */
    void start() {
        watcher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Resource Tracker game link");
            t.setDaemon(true);
            return t;
        });
        watcher.scheduleWithFixedDelay(() -> {
            try {
                var found = links.scanFolder();
                ProgressFolder.Snapshot s = tracker.progressFolder.scan();
                if (!closed) ctx.runOnUiThread(() -> {
                    if (closed) return;
                    links.scanned(found);
                    gameScanned(s);
                });
            } catch (RuntimeException e) {
                // try again next time; a failure must not stop the watch
            }
        }, 0, 2, TimeUnit.SECONDS);
        updateStatus();
    }

    void close() {
        closed = true;
        if (watcher != null) watcher.shutdownNow();
        watcher = null;
        links.removeListener(linksChanged);
        links.close();
        listeners.clear();
    }

    void addListener(Listener l) {
        listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void fire(boolean recount) {
        for (Listener l : listeners) l.countChanged(recount);
    }

    private void gameScanned(ProgressFolder.Snapshot s) {
        if (s.equals(tracker.game())) {
            for (Listener l : listeners) l.tick();
            return;
        }
        tracker.gameScanned(s);
        fire(false);
    }

    /** The games reported something: the link may stand differently, and their chests may hold other things. */
    private void linksChanged() {
        updateStatus();
        var now = links.chests();
        if (tracker.hasChests() || !now.isEmpty()) {
            tracker.setChests(now);
            fire(false);
        }
    }

    /** The dot on the BlockCompanion page's button: green connected, yellow connecting, grey waiting. */
    private void updateStatus() {
        LinkStatus s = LinkStatus.of(links);
        ctx.setPanelStatus(GAME_PAGE, s.tone(), s.title());
    }

    /** The project was linked to another build in the game: what counts as placed changed. */
    void linkChanged() {
        fire(false);
    }

    void setMobs(boolean mobs) {
        if (tracker.mobs == mobs) return;
        tracker.mobs = mobs;
        fire(true);
    }

    /** The game's progress and chests looked at again, and the build counted again. */
    void recountAll() {
        tracker.gameScanned(tracker.progressFolder.scan());
        tracker.setChests(links.chests());
        fire(true);
    }

    /** Looks for games again and asks them for their status, then counts again. */
    void refreshAll() {
        links.refresh();
        recountAll();
    }

    /** Copies what is left to gather as text (the Materials page's button and the Plugins menu action). */
    void copyList() {
        tracker.gameScanned(tracker.progressFolder.scan());   // what is placed in the game, if linked
        tracker.setChests(links.chests());
        tracker.recount();
        ctx.ui().copyText(tracker.text(), "Materials list copied");
    }

    /** Sends the project to the ticked games; says how it went. */
    void send() {
        int n = links.send(true, null);
        ctx.toast(n == 0 ? "No connected game is ticked: start Minecraft with BlockCompanion" : "Sent to " + n + (n == 1 ? " game" : " games"));
    }
}

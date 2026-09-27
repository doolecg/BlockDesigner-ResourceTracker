package io.blockdesigner.resources;

import io.blockdesigner.plugin.BlockDesignerPlugin;
import io.blockdesigner.plugin.PluginAction;
import io.blockdesigner.plugin.PluginContext;
import io.blockdesigner.plugin.SceneEvent;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Resource Tracker: the materials a build needs as the items you'd gather in survival, what has been gathered and
 * what's left, per project. A Materials panel in the plugin's tab, a Plugins menu entry to copy the list, and the live
 * link to BlockCompanion games (it looks for them every 2 seconds from the start, so a game's Grab button works while
 * the panel is closed).
 */
public final class ResourceTrackerPlugin implements BlockDesignerPlugin {
    private ScheduledExecutorService watcher;
    private GameLinks links;

    @Override
    public void enable(PluginContext ctx) {
        Tracker tracker = new Tracker(ctx);
        links = new GameLinks(ctx, ctx.info().version());
        // Follow the open project from the start, before the panel is first shown.
        ctx.on(SceneEvent.ProjectOpened.class, e -> {
            tracker.projectOpened(e.file());
            links.projectOpened(e.file(), tracker.projectName());
        });
        ctx.registerPanel(new TrackerPanel(tracker, links));
        ctx.registerAction(new PluginAction("Copy materials list", "Copies what is left to gather as text (what the Materials panel counts)", () -> {
            tracker.gameScanned(tracker.progressFolder.scan());   // what is placed in the game, if linked
            tracker.setChests(links.chests());
            tracker.recount();
            ClipboardContent c = new ClipboardContent();
            c.putString(tracker.text());
            Clipboard.getSystemClipboard().setContent(c);
            ctx.toast("Materials list copied");
        }));
        ctx.registerAction(new PluginAction("Send project to the game", "Sends this project to the ticked BlockCompanion games", () -> {
            int n = links.send(true, null);
            ctx.toast(n == 0 ? "No connected game: start Minecraft with BlockCompanion" : "Sent to " + n + (n == 1 ? " game" : " games"));
        }));
        watcher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Resource Tracker game link");
            t.setDaemon(true);
            return t;
        });
        GameLinks l = links;
        watcher.scheduleWithFixedDelay(() -> {
            try {
                var found = l.scanFolder();
                ctx.runOnUiThread(() -> l.scanned(found));
            } catch (RuntimeException e) {
                // try again next time
            }
        }, 0, 2, TimeUnit.SECONDS);
    }

    @Override
    public void disable() {
        if (watcher != null) watcher.shutdownNow();
        watcher = null;
        if (links != null) links.close();
        links = null;
    }
}

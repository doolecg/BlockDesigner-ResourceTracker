package io.blockdesigner.resources;

import io.blockdesigner.plugin.BlockDesignerPlugin;
import io.blockdesigner.plugin.PluginAction;
import io.blockdesigner.plugin.PluginContext;
import io.blockdesigner.plugin.SceneEvent;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;

/**
 * Resource Tracker: the materials a build needs as the items you'd gather in survival, what has been gathered and
 * what's left, per project. A Materials panel in the plugin's tab, and a Plugins menu entry to copy the list.
 */
public final class ResourceTrackerPlugin implements BlockDesignerPlugin {
    @Override
    public void enable(PluginContext ctx) {
        Tracker tracker = new Tracker(ctx);
        // Follow the open project from the start, before the panel is first shown.
        ctx.on(SceneEvent.ProjectOpened.class, e -> tracker.projectOpened(e.file()));
        ctx.registerPanel(new TrackerPanel(tracker));
        ctx.registerAction(new PluginAction("Copy materials list", "Copies what is left to gather as text (what the Materials panel counts)", () -> {
            tracker.gameScanned(tracker.progressFolder.scan());   // what is placed in the game, if linked
            tracker.recount();
            ClipboardContent c = new ClipboardContent();
            c.putString(tracker.text());
            Clipboard.getSystemClipboard().setContent(c);
            ctx.toast("Materials list copied");
        }));
    }
}

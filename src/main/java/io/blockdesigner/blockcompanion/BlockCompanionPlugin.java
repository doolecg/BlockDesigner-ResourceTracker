package io.blockdesigner.blockcompanion;

import io.blockdesigner.plugin.BlockDesignerPlugin;
import io.blockdesigner.plugin.PluginAction;
import io.blockdesigner.plugin.PluginContext;

/**
 * BlockCompanion Plugin: the materials a build needs as the items you'd gather in survival, what has been gathered and
 * what's left, per project. Its tab has a Materials page and a BlockCompanion page (the live link to BlockCompanion
 * games; it looks for them every 2 seconds from the start, so a game's Grab button works while the tab is closed and
 * the page's dot shows how the link stands), a setting on its page in the Settings window, and Plugins menu entries
 * to copy the list and send the project.
 */
public final class BlockCompanionPlugin implements BlockDesignerPlugin {
    private TrackerSession session;

    @Override
    public void enable(PluginContext ctx) {
        TrackerSession s = new TrackerSession(ctx);
        session = s;
        s.registerSettings();
        ctx.registerPanel(new TrackerPanel(s));
        ctx.registerPanel(new GameLinkPanel(s));
        ctx.registerAction(new PluginAction("Copy materials list", "Copies what is left to gather as text (what the Materials page counts)",
                s::copyList));
        ctx.registerAction(new PluginAction("Send project to the game", "Sends this project to the ticked BlockCompanion games", s::send));
        s.start();
    }

    @Override
    public void disable() {
        if (session != null) session.close();
        session = null;
    }
}

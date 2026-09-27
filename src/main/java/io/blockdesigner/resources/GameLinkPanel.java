package io.blockdesigner.resources;

import io.blockdesigner.plugin.PanelContext;
import io.blockdesigner.plugin.PluginPanel;
import javafx.scene.Node;

/**
 * The BlockCompanion page of the plugin's tab ({@link GameLinkPane}): the live link to BlockCompanion games. Its page
 * button carries a dot for how the link stands, set by {@link TrackerSession} before the page is ever opened.
 */
final class GameLinkPanel implements PluginPanel {
    private final TrackerSession session;
    private GameLinkPane pane;
    private final TrackerSession.Listener listener = new TrackerSession.Listener() {
        @Override
        public void countChanged(boolean recount) {
            // Another project, or the game wrote progress: other builds to pick and another automatic match.
            if (pane != null) pane.updateChoices();
        }

        @Override
        public void tick() {
            if (pane != null) pane.showGame();
        }
    };

    GameLinkPanel(TrackerSession session) {
        this.session = session;
    }

    @Override
    public String id() {
        return TrackerSession.GAME_PAGE;
    }

    @Override
    public String title() {
        return "BlockCompanion";
    }

    @Override
    public String icon() {
        // Two links of a chain.
        return "M6.5 9.5 L9.5 6.5 M7 4.5 L8.5 3 A2.5 2.5 0 0 1 13 7.5 L11.5 9 M9 11.5 L7.5 13 A2.5 2.5 0 0 1 3 8.5 L4.5 7";
    }

    @Override
    public Node create(PanelContext context) {
        pane = new GameLinkPane(session, session::linkChanged);
        Node page = pane.create();
        session.addListener(listener);
        return page;
    }

    @Override
    public void dispose() {
        session.removeListener(listener);
        if (pane != null) pane.dispose();
        pane = null;
    }
}

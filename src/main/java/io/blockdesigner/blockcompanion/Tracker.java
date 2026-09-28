package io.blockdesigner.blockcompanion;

import io.blockdesigner.core.model.BlockState;
import io.blockdesigner.core.model.Box;
import io.blockdesigner.core.model.Layer;
import io.blockdesigner.plugin.PluginContext;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipFile;

/**
 * The tracker's state, shared by its panel and menu entries: where it counts, what the build needs there, what has
 * been gathered for the open project, and what is placed in the game (from the BlockCompanion build it's linked to).
 */
final class Tracker {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final PluginContext ctx;
    final Gathered gathered;
    final ProgressFolder progressFolder;
    Tally.Scope scope = Tally.Scope.VISIBLE;
    boolean mobs;
    private Optional<Path> project = Optional.empty();
    private String projectName;
    private List<Tally.Need> needs = List.of();
    private ProgressFolder.Snapshot game = ProgressFolder.Snapshot.NONE;
    private GameProgress linked;
    /** Items in the linked chests of the connected games (BlockCompanion), by item id. */
    private Map<String, Long> chests = Map.of();

    Tracker(PluginContext ctx) {
        this.ctx = ctx;
        this.gathered = new Gathered(ctx.dataFolder());
        this.progressFolder = new ProgressFolder(ProgressFolder.defaultFolder());
        gathered.open(project);
    }

    /** Another project was opened (or a new one started): its gathered counts and game link come back. */
    void projectOpened(Optional<Path> file) {
        project = file;
        projectName = file.map(Tracker::projectName).orElse(null);
        gathered.open(file);
        resolve();
    }

    // --- The game (BlockCompanion's progress files) ---

    /** A new look at the progress folder (on the JavaFX thread). */
    void gameScanned(ProgressFolder.Snapshot snapshot) {
        game = snapshot;
        resolve();
    }

    ProgressFolder.Snapshot game() {
        return game;
    }

    /** The build in the game this project is linked to, or null. */
    GameProgress linked() {
        return linked;
    }

    Gathered.Link link() {
        return gathered.link();
    }

    /** The build that would be linked automatically, if any. */
    Optional<GameProgress> autoMatch() {
        return ProgressFolder.match(game.builds(), projectName, project.map(p -> p.getFileName().toString()).orElse(null));
    }

    void setLink(Gathered.Link link) {
        gathered.setLink(link);
        save();
        resolve();
    }

    private void resolve() {
        Gathered.Link l = gathered.link();
        linked = switch (l.mode()) {
            case OFF -> null;
            case FILE -> game.byFileName(l.file()).orElse(null);
            case AUTO -> autoMatch().orElse(null);
        };
    }

    /** What the games' linked chests hold now. */
    void setChests(Map<String, Long> items) {
        chests = Map.copyOf(items);
    }

    /** How many of the item the linked chests hold. */
    long chests(String item) {
        return chests.getOrDefault(item, 0L);
    }

    boolean hasChests() {
        return !chests.isEmpty();
    }

    /** The open project's name as BlockCompanion shows it, or null for a new project. */
    String projectName() {
        return projectName;
    }

    Optional<Path> project() {
        return project;
    }

    /** How many of the item are placed in the linked build (0 when none is linked). */
    long placed(String item) {
        return linked == null ? 0 : linked.placed(item);
    }

    /**
     * The name BlockCompanion shows for a project file: the {@code name} in a {@code .bdproj}'s {@code project.json},
     * else the file name without its extension.
     */
    static String projectName(Path file) {
        String fileName = file.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".bdproj")) return stem;
        try (ZipFile zip = new ZipFile(file.toFile())) {
            var entry = zip.getEntry("project.json");
            if (entry == null) return stem;
            try (InputStream in = zip.getInputStream(entry)) {
                String name = JSON.readTree(in).path("name").asText("");
                return name.isBlank() ? stem : name.strip();
            }
        } catch (IOException | RuntimeException e) {
            return stem;
        }
    }

    /** Counts again what the build needs in the current scope. */
    List<Tally.Need> recount() {
        List<Layer> layers = new ArrayList<>();
        Box within = null;
        switch (scope) {
            case VISIBLE, SELECTION -> ctx.scene().layers().stream().filter(Layer::visible).forEach(layers::add);
            case SELECTED -> layers.addAll(ctx.selectedLayers());
            case ACTIVE -> ctx.activeLayer().ifPresent(layers::add);
        }
        if (scope == Tally.Scope.SELECTION) {
            Optional<Box> sel = ctx.selection();
            if (sel.isEmpty()) {
                needs = List.of();
                return needs;
            }
            within = sel.get();
        }
        needs = Tally.count(layers, within, mobs);
        return needs;
    }

    List<Tally.Need> needs() {
        return needs;
    }

    /** Left to get: needed less placed, gathered and what the linked chests hold. */
    long left(Tally.Need n) {
        return left(n.count(), placed(n.item()), gathered.get(n.item()) + chests(n.item()));
    }

    /**
     * What is left to get: {@code max(0, needed - placed - gathered)}. Gathered means in hand and not placed yet, so
     * placed and gathered never count the same item twice.
     */
    static long left(long needed, long placed, long gathered) {
        return Math.max(0, needed - Math.max(0, placed) - Math.max(0, gathered));
    }

    /** How much of the item is covered, placed or gathered, capped at what is needed (for the progress bar). */
    static long covered(long needed, long placed, long gathered) {
        return needed - left(needed, placed, gathered);
    }

    /** What "gathered all of it" sets: whatever isn't placed yet. */
    static long rest(long needed, long placed) {
        return Math.max(0, needed - Math.max(0, placed));
    }

    /** What "gathered all of it" sets when chests hold some: whatever isn't placed or in the chests. */
    static long rest(long needed, long placed, long inChests) {
        return Math.max(0, needed - Math.max(0, placed) - Math.max(0, inChests));
    }

    void setGathered(String item, long n) {
        gathered.set(item, n);
        save();
    }

    void resetGathered() {
        gathered.clear();
        save();
    }

    private void save() {
        try {
            gathered.save(project);
        } catch (IOException e) {
            ctx.log("Could not save gathered counts: " + e.getMessage());
        }
    }

    /** The item's name: the block's display name when it is a block, else its id tidied up. */
    String name(String item) {
        try {
            if (ctx.blocks().exists(item)) return ctx.blocks().displayName(BlockState.of(item));
        } catch (RuntimeException ignored) {
            // fall back to the id
        }
        return Items.pretty(item);
    }

    /** The list as plain text: one line per item still needed ("Oak Planks: 640 (10 stacks)"). */
    String text() {
        StringBuilder b = new StringBuilder("Materials (" + scope.label.toLowerCase(Locale.ROOT) + ")\n");
        for (Tally.Need n : needs) {
            long left = left(n);
            if (left == 0) continue;
            b.append(name(n.item())).append(": ").append(String.format("%,d", left));
            String st = Items.stacks(left, n.item());
            if (!st.equals(String.format("%,d", left))) b.append(" (").append(st).append(')');
            if (linked != null) b.append(" · ").append(String.format("%,d", placed(n.item()))).append(" placed");
            if (chests(n.item()) > 0) b.append(" · ").append(String.format("%,d", chests(n.item()))).append(" in chests");
            b.append('\n');
        }
        return b.toString();
    }

    /** The list as CSV: item, name, needed, placed (when linked to the game), gathered, left, as stacks. */
    String csv() {
        boolean game = linked != null, withChests = hasChests();
        StringBuilder b = new StringBuilder("item,name,needed," + (game ? "placed," : "") + "gathered," + (withChests ? "in chests," : "")
                + "left,left as stacks\n");
        for (Tally.Need n : needs) {
            long have = gathered.get(n.item()), left = left(n);
            b.append(n.item()).append(',').append(quote(name(n.item()))).append(',').append(n.count()).append(',');
            if (game) b.append(placed(n.item())).append(',');
            b.append(have).append(',');
            if (withChests) b.append(chests(n.item())).append(',');
            b.append(left).append(',').append(quote(Items.stacks(left, n.item()))).append('\n');
        }
        return b.toString();
    }

    private static String quote(String s) {
        return s.contains(",") || s.contains("\"") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    /**
     * A typed amount: a number ({@code 640}), stacks ({@code 10s}), shulker boxes ({@code 2sh}), or a sum of them
     * ({@code 1sh + 3s + 12}). Empty for text that isn't one.
     */
    static Optional<Long> parse(String text, String item) {
        if (text == null) return Optional.empty();
        String t = text.strip().toLowerCase(Locale.ROOT).replace(",", "").replace(" ", "");
        if (t.isEmpty()) return Optional.of(0L);
        int size = Items.stackSize(item);
        long total = 0;
        for (String part : t.split("\\+")) {
            if (part.isEmpty()) return Optional.empty();
            long mult = 1;
            String num = part;
            if (part.endsWith("sh")) {
                mult = 27L * size;
                num = part.substring(0, part.length() - 2);
            } else if (part.endsWith("s")) {
                mult = size;
                num = part.substring(0, part.length() - 1);
            }
            try {
                total += Long.parseLong(num) * mult;
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        return Optional.of(Math.max(0, total));
    }
}

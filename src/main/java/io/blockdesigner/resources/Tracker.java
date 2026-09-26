package io.blockdesigner.resources;

import io.blockdesigner.core.model.BlockState;
import io.blockdesigner.core.model.Box;
import io.blockdesigner.core.model.Layer;
import io.blockdesigner.plugin.PluginContext;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The tracker's state, shared by its panel and menu entries: where it counts, what the build needs there, and what
 * has been gathered for the open project.
 */
final class Tracker {
    private final PluginContext ctx;
    final Gathered gathered;
    Tally.Scope scope = Tally.Scope.VISIBLE;
    boolean mobs;
    private Optional<Path> project = Optional.empty();
    private List<Tally.Need> needs = List.of();

    Tracker(PluginContext ctx) {
        this.ctx = ctx;
        this.gathered = new Gathered(ctx.dataFolder());
        gathered.open(project);
    }

    /** Another project was opened (or a new one started): its gathered counts come back. */
    void projectOpened(Optional<Path> file) {
        project = file;
        gathered.open(file);
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

    long left(Tally.Need n) {
        return Math.max(0, n.count() - gathered.get(n.item()));
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
            b.append('\n');
        }
        return b.toString();
    }

    /** The list as CSV: item, name, needed, gathered, left, as stacks. */
    String csv() {
        StringBuilder b = new StringBuilder("item,name,needed,gathered,left,left as stacks\n");
        for (Tally.Need n : needs) {
            long have = gathered.get(n.item()), left = left(n);
            b.append(n.item()).append(',').append(quote(name(n.item()))).append(',').append(n.count()).append(',').append(have)
                    .append(',').append(left).append(',').append(quote(Items.stacks(left, n.item()))).append('\n');
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

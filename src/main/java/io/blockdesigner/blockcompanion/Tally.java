package io.blockdesigner.blockcompanion;

import io.blockdesigner.core.model.BlockPos;
import io.blockdesigner.core.model.BlockState;
import io.blockdesigner.core.model.Box;
import io.blockdesigner.core.model.Layer;
import io.blockdesigner.core.model.StructureEntity;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Adds up the items a set of layers (or the part of them inside a box) costs. */
final class Tally {
    private Tally() {
    }

    /** Where to count. */
    enum Scope {
        VISIBLE("All visible layers"), SELECTED("Selected layers"), ACTIVE("Active layer"), SELECTION("The selection");

        final String label;

        Scope(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** An item the build needs: how many, and a block that shows it (for its icon), or null. */
    record Need(String item, long count, BlockState icon) {
    }

    /**
     * The items {@code layers} cost, most first. With {@code within}, only blocks and entities inside that world box
     * count. Mobs count as spawn eggs when {@code mobs} is set.
     */
    static List<Need> count(List<Layer> layers, Box within, boolean mobs) {
        Map<BlockState, Long> states = new HashMap<>();
        Map<String, Long> fromEntities = new LinkedHashMap<>();
        for (Layer l : layers) {
            if (within == null) {
                // One pass over each palette-indexed section, no callback per block.
                l.structure().stateCounts().forEach((st, n) -> states.merge(st, n, Long::sum));
            } else {
                countInside(l, within, states);
            }
            for (StructureEntity e : l.structure().entities()) {
                if (within != null) {
                    BlockPos w = l.toWorld((int) Math.floor(e.x()), (int) Math.floor(e.y()), (int) Math.floor(e.z()));
                    if (!within.contains(w.x(), w.y(), w.z())) continue;
                }
                Items.forEntity(e, mobs).forEach((item, n) -> fromEntities.merge(item, (long) n, Long::sum));
            }
        }
        Map<String, Long> totals = new LinkedHashMap<>();
        Map<String, BlockState> icons = new HashMap<>();
        states.forEach((st, n) -> Items.forBlock(st).forEach((item, per) -> {
            totals.merge(item, per * n, Long::sum);
            // The block placed by the item shows it: its own id when it is a block, else the block it came from.
            BlockState icon = item.equals(st.name()) ? BlockState.of(st.name()) : st;
            icons.merge(item, icon, (a, b) -> a.name().equals(item) ? a : b);
        }));
        fromEntities.forEach((item, n) -> totals.merge(item, n, Long::sum));
        return totals.entrySet().stream()
                .map(e -> new Need(e.getKey(), e.getValue(), icons.get(e.getKey())))
                .sorted((a, b) -> a.count() != b.count() ? Long.compare(b.count(), a.count()) : a.item().compareTo(b.item()))
                .toList();
    }

    /** Counts a layer's blocks inside a world box, walking only the layer-local cells the box can cover. */
    private static void countInside(Layer l, Box world, Map<BlockState, Long> states) {
        var bounds = l.worldBounds();
        if (bounds.isEmpty()) return;
        Box b = bounds.get();
        int x0 = Math.max(world.minX(), b.minX()), y0 = Math.max(world.minY(), b.minY()), z0 = Math.max(world.minZ(), b.minZ());
        int x1 = Math.min(world.maxX(), b.maxX()), y1 = Math.min(world.maxY(), b.maxY()), z1 = Math.min(world.maxZ(), b.maxZ());
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    BlockState st = l.structure().get(l.toLocal(new BlockPos(x, y, z)));
                    if (!st.isAir()) states.merge(st, 1L, Long::sum);
                }
            }
        }
    }
}

package io.blockdesigner.blockcompanion;

import io.blockdesigner.core.model.BlockState;
import io.blockdesigner.core.model.StructureEntity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * What a build costs in survival: the items each placed block (or entity) takes. Most blocks are their own item, but
 * a double slab is two slabs, a door or bed is one item across two blocks, a crop is its seeds, a wall torch is a
 * torch, candles and sea pickles come in bunches, and some blocks (fire, portals, piston heads) cost nothing.
 */
final class Items {
    private Items() {
    }

    /** Blocks no item places, or that cost nothing extra (the rest of a structure made them). */
    private static final Set<String> FREE = Set.of("air", "cave_air", "void_air", "moving_piston", "piston_head", "bubble_column",
            "fire", "soul_fire", "nether_portal", "end_portal", "end_gateway", "frosted_ice", "structure_void");

    /** Blocks placed by an item of another name. */
    private static final Map<String, String> PLACED_BY = Map.ofEntries(
            Map.entry("wheat", "wheat_seeds"), Map.entry("carrots", "carrot"), Map.entry("potatoes", "potato"),
            Map.entry("beetroots", "beetroot_seeds"), Map.entry("melon_stem", "melon_seeds"), Map.entry("attached_melon_stem", "melon_seeds"),
            Map.entry("pumpkin_stem", "pumpkin_seeds"), Map.entry("attached_pumpkin_stem", "pumpkin_seeds"), Map.entry("cocoa", "cocoa_beans"),
            Map.entry("sweet_berry_bush", "sweet_berries"), Map.entry("cave_vines", "glow_berries"), Map.entry("cave_vines_plant", "glow_berries"),
            Map.entry("kelp_plant", "kelp"), Map.entry("twisting_vines_plant", "twisting_vines"), Map.entry("weeping_vines_plant", "weeping_vines"),
            Map.entry("bamboo_sapling", "bamboo"), Map.entry("torchflower_crop", "torchflower_seeds"), Map.entry("pitcher_crop", "pitcher_pod"),
            Map.entry("big_dripleaf_stem", "big_dripleaf"), Map.entry("tripwire", "string"), Map.entry("redstone_wire", "redstone"),
            Map.entry("powder_snow", "powder_snow_bucket"), Map.entry("farmland", "dirt"), Map.entry("dirt_path", "dirt"),
            Map.entry("water_cauldron", "cauldron"), Map.entry("lava_cauldron", "cauldron"), Map.entry("powder_snow_cauldron", "cauldron"),
            Map.entry("water", "water_bucket"), Map.entry("lava", "lava_bucket"), Map.entry("tall_seagrass", "seagrass"),
            Map.entry("potted_azalea_bush", "azalea"), Map.entry("potted_flowering_azalea_bush", "flowering_azalea"));

    /** Properties that say how many of the item one block holds. */
    private static final String[] AMOUNTS = {"candles", "pickles", "eggs", "layers", "flower_amount", "segment_amount"};

    /** Items that stack to 16 or not at all (the rest stack to 64). */
    private static final Set<String> STACK_16 = Set.of("armor_stand", "snowball", "egg", "blue_egg", "brown_egg", "ender_pearl", "honey_bottle");

    /** The items one block costs (item id, count); empty for blocks that cost nothing. Ids keep their namespace. */
    static Map<String, Integer> forBlock(BlockState s) {
        Map<String, Integer> out = new LinkedHashMap<>();
        String ns = s.namespace(), p = s.path();
        if (FREE.contains(p) && ns.equals("minecraft")) return out;
        // Fluids: only a source is a bucket's worth; flowing water and lava come free.
        if ((p.equals("water") || p.equals("lava")) && !"0".equals(s.get("level")) && s.has("level")) return out;
        // The second half of a door, tall flower, bed, pitcher plant… comes with the first.
        if ("upper".equals(s.get("half")) || "head".equals(s.get("part"))) return out;

        if (p.startsWith("potted_") && !PLACED_BY.containsKey(p)) {
            out.put(ns + ":flower_pot", 1);
            out.put(ns + ":" + p.substring("potted_".length()), 1);
            return out;
        }
        if (p.startsWith("potted_")) {
            out.put(ns + ":flower_pot", 1);
            out.put(ns + ":" + PLACED_BY.get(p), 1);
            return out;
        }
        if (p.endsWith("candle_cake")) {
            out.put(ns + ":cake", 1);
            out.put(ns + ":" + (p.equals("candle_cake") ? "candle" : p.substring(0, p.length() - "_cake".length())), 1);
            return out;
        }

        String item = ns.equals("minecraft") ? PLACED_BY.getOrDefault(p, standing(p)) : standing(p);
        int n = 1;
        if ("double".equals(s.get("type")) && p.endsWith("slab")) n = 2;
        for (String k : AMOUNTS) {
            String v = s.get(k);
            if (v == null) continue;
            try {
                n = Math.max(1, Integer.parseInt(v));
            } catch (NumberFormatException ignored) {
                // not a count
            }
        }
        out.put(ns + ":" + item, n);
        return out;
    }

    /** A wall-mounted block's item: wall_torch → torch, oak_wall_sign → oak_sign, tube_coral_wall_fan → tube_coral_fan. */
    static String standing(String path) {
        if (path.equals("wall_torch")) return "torch";
        if (path.endsWith("_wall_torch")) return path.substring(0, path.length() - "_wall_torch".length()) + "_torch";
        for (String kind : new String[]{"hanging_sign", "sign", "banner", "head", "skull", "fan"}) {
            String wall = "_wall_" + kind;
            if (path.endsWith(wall)) return path.substring(0, path.length() - wall.length()) + "_" + kind;
        }
        return path;
    }

    /**
     * The items an entity costs: a painting, frame (and what it holds), armor stand, boat or minecart is its item; a mob
     * is its spawn egg when {@code mobs} is set, else nothing.
     */
    static Map<String, Integer> forEntity(StructureEntity e, boolean mobs) {
        Map<String, Integer> out = new LinkedHashMap<>();
        String id = e.id();
        if (id == null || id.isBlank()) return out;
        if (id.indexOf(':') < 0) id = "minecraft:" + id;
        String ns = id.substring(0, id.indexOf(':')), p = id.substring(id.indexOf(':') + 1);
        boolean thing = p.equals("painting") || p.endsWith("item_frame") || p.equals("armor_stand") || p.endsWith("minecart")
                || p.endsWith("_boat") || p.endsWith("_raft") || p.equals("end_crystal") || p.equals("leash_knot");
        if (thing) {
            out.put(ns + ":" + (p.equals("leash_knot") ? "lead" : p), 1);
            // What a frame shows costs its item too.
            var held = e.nbt().getCompound("Item");
            String inside = held.getString("id");
            if (p.endsWith("item_frame") && !inside.isBlank()) {
                int count = Math.max(1, held.getInt("count", held.getInt("Count", 1)));
                out.merge(inside.indexOf(':') < 0 ? "minecraft:" + inside : inside, count, Integer::sum);
            }
            return out;
        }
        if (mobs) out.put(ns + ":" + p + "_spawn_egg", 1);
        return out;
    }

    /** How many of an item fit in one inventory slot. */
    static int stackSize(String item) {
        String p = item.substring(item.indexOf(':') + 1);
        if (p.endsWith("_bed") || p.equals("cake") || p.endsWith("_boat") || p.endsWith("_raft") || p.endsWith("minecart")
                || p.endsWith("_bucket") || p.endsWith("shulker_box") || p.equals("saddle")) return 1;
        if (p.endsWith("_sign") || p.endsWith("_banner") || STACK_16.contains(p)) return 16;
        return 64;
    }

    /** "3 stacks + 12", "1 shulker + 2 stacks", "40": a count as a player carries it. */
    static String stacks(long count, String item) {
        int size = stackSize(item);
        if (count < size || size == 1) return String.format("%,d", count);
        long stacks = count / size, rest = count % size;
        long shulkers = stacks / 27;
        stacks %= 27;
        StringBuilder b = new StringBuilder();
        if (shulkers > 0) b.append(shulkers).append(shulkers == 1 ? " shulker" : " shulkers");
        if (stacks > 0) b.append(b.isEmpty() ? "" : " + ").append(stacks).append(stacks == 1 ? " stack" : " stacks");
        if (rest > 0) b.append(b.isEmpty() ? "" : " + ").append(rest);
        return b.toString();
    }

    /** "oak_planks" → "Oak Planks", for items that aren't blocks (seeds, string, spawn eggs). */
    static String pretty(String item) {
        String p = item.substring(item.indexOf(':') + 1);
        StringBuilder sb = new StringBuilder();
        for (String w : p.split("[_/]")) {
            if (w.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }
}

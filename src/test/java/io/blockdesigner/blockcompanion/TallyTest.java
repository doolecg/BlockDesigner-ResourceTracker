package io.blockdesigner.blockcompanion;

import io.blockdesigner.core.model.BlockPos;
import io.blockdesigner.core.model.BlockState;
import io.blockdesigner.core.model.Box;
import io.blockdesigner.core.model.Layer;
import io.blockdesigner.core.model.Structure;
import io.blockdesigner.core.model.StructureEntity;
import io.blockdesigner.core.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class TallyTest {
    private static Map<String, Long> asMap(List<Tally.Need> needs) {
        return needs.stream().collect(Collectors.toMap(Tally.Need::item, Tally.Need::count));
    }

    private static Layer house() {
        Structure s = new Structure();
        for (int x = 0; x < 4; x++) for (int z = 0; z < 4; z++) s.set(x, 0, z, BlockState.of("oak_planks"));
        s.set(0, 1, 0, BlockState.parse("oak_door[half=lower,facing=north,hinge=left,open=false,powered=false]"));
        s.set(0, 2, 0, BlockState.parse("oak_door[half=upper,facing=north,hinge=left,open=false,powered=false]"));
        s.set(1, 1, 0, BlockState.parse("stone_slab[type=double,waterlogged=false]"));
        s.addEntity(new StructureEntity(2.5, 1, 2.5, new CompoundTag().putString("id", "minecraft:villager")));
        s.addEntity(new StructureEntity(3.5, 1, 3.5, new CompoundTag().putString("id", "minecraft:armor_stand")));
        return new Layer("house", s);
    }

    @Test
    void countsItemsMostFirst() {
        List<Tally.Need> needs = Tally.count(List.of(house()), null, false);
        assertThat(needs.getFirst().item()).isEqualTo("minecraft:oak_planks");
        assertThat(asMap(needs)).containsExactlyInAnyOrderEntriesOf(Map.of("minecraft:oak_planks", 16L, "minecraft:oak_door", 1L,
                "minecraft:stone_slab", 2L, "minecraft:armor_stand", 1L));
        assertThat(asMap(Tally.count(List.of(house()), null, true))).containsEntry("minecraft:villager_spawn_egg", 1L);
    }

    @Test
    void onlyInsideTheBox() {
        Layer l = house();
        l.setOffset(new BlockPos(10, 0, 0));
        // World x 10..11, y 0..1, z 0: two planks, the door's lower half and the double slab.
        Map<String, Long> m = asMap(Tally.count(List.of(l), new Box(10, 0, 0, 11, 1, 0), false));
        assertThat(m).containsExactlyInAnyOrderEntriesOf(Map.of("minecraft:oak_planks", 2L, "minecraft:oak_door", 1L, "minecraft:stone_slab", 2L));
    }

    @Test
    void typedAmounts() {
        assertThat(Tracker.parse("640", "minecraft:stone")).contains(640L);
        assertThat(Tracker.parse("10s", "minecraft:stone")).contains(640L);
        assertThat(Tracker.parse("1sh + 3s + 12", "minecraft:stone")).contains(64L * 27 + 3 * 64 + 12);
        assertThat(Tracker.parse("2s", "minecraft:oak_sign")).contains(32L);
        assertThat(Tracker.parse("", "minecraft:stone")).contains(0L);
        assertThat(Tracker.parse("lots", "minecraft:stone")).isEmpty();
    }

    @Test
    void gatheredCountsAreKeptPerProject(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Gathered g = new Gathered(dir);
        Optional<Path> a = Optional.of(dir.resolve("a.bdproj")), b = Optional.of(dir.resolve("b.bdproj"));
        g.open(a);
        g.set("minecraft:stone", 100);
        g.save(a);
        g.open(b);
        assertThat(g.get("minecraft:stone")).isZero();
        g.open(a);
        assertThat(g.get("minecraft:stone")).isEqualTo(100);
        g.clear();
        g.save(a);
        g.open(a);
        assertThat(g.isEmpty()).isTrue();
    }
}

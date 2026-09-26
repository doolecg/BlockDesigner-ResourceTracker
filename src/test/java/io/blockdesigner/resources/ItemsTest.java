package io.blockdesigner.resources;

import io.blockdesigner.core.model.BlockState;
import io.blockdesigner.core.model.StructureEntity;
import io.blockdesigner.core.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ItemsTest {
    private static Map<String, Integer> items(String state) {
        return Items.forBlock(BlockState.parse(state));
    }

    @Test
    void mostBlocksAreTheirOwnItem() {
        assertThat(items("minecraft:oak_planks")).containsExactly(Map.entry("minecraft:oak_planks", 1));
        assertThat(items("minecraft:oak_stairs[facing=east,half=top,shape=straight]")).containsExactly(Map.entry("minecraft:oak_stairs", 1));
        assertThat(items("create:shaft[axis=y]")).containsExactly(Map.entry("create:shaft", 1));
    }

    @Test
    void doubleSlabsAreTwoAndTwoBlockThingsCountOnce() {
        assertThat(items("minecraft:stone_slab[type=double]")).containsExactly(Map.entry("minecraft:stone_slab", 2));
        assertThat(items("minecraft:stone_slab[type=top]")).containsExactly(Map.entry("minecraft:stone_slab", 1));
        assertThat(items("minecraft:oak_door[half=lower,facing=north,hinge=left,open=false,powered=false]")).containsEntry("minecraft:oak_door", 1);
        assertThat(items("minecraft:oak_door[half=upper,facing=north,hinge=left,open=false,powered=false]")).isEmpty();
        assertThat(items("minecraft:red_bed[part=head,facing=north,occupied=false]")).isEmpty();
        assertThat(items("minecraft:red_bed[part=foot,facing=north,occupied=false]")).containsEntry("minecraft:red_bed", 1);
        assertThat(items("minecraft:sunflower[half=upper]")).isEmpty();
    }

    @Test
    void cropsWallThingsAndBunches() {
        assertThat(items("minecraft:wheat[age=7]")).containsExactly(Map.entry("minecraft:wheat_seeds", 1));
        assertThat(items("minecraft:wall_torch[facing=north]")).containsExactly(Map.entry("minecraft:torch", 1));
        assertThat(items("minecraft:soul_wall_torch[facing=north]")).containsExactly(Map.entry("minecraft:soul_torch", 1));
        assertThat(items("minecraft:oak_wall_hanging_sign[facing=north]")).containsExactly(Map.entry("minecraft:oak_hanging_sign", 1));
        assertThat(items("minecraft:tube_coral_wall_fan[facing=north]")).containsExactly(Map.entry("minecraft:tube_coral_fan", 1));
        assertThat(items("minecraft:candle[candles=3,lit=false]")).containsExactly(Map.entry("minecraft:candle", 3));
        assertThat(items("minecraft:snow[layers=5]")).containsExactly(Map.entry("minecraft:snow", 5));
        assertThat(items("minecraft:redstone_wire[power=0]")).containsExactly(Map.entry("minecraft:redstone", 1));
        assertThat(items("minecraft:potted_poppy")).containsExactly(Map.entry("minecraft:flower_pot", 1), Map.entry("minecraft:poppy", 1));
        assertThat(items("minecraft:red_candle_cake[lit=false]")).containsExactly(Map.entry("minecraft:cake", 1), Map.entry("minecraft:red_candle", 1));
    }

    @Test
    void freeBlocksAndFluids() {
        assertThat(items("minecraft:air")).isEmpty();
        assertThat(items("minecraft:fire[age=0]")).isEmpty();
        assertThat(items("minecraft:piston_head[facing=up,short=false,type=normal]")).isEmpty();
        assertThat(items("minecraft:water[level=0]")).containsExactly(Map.entry("minecraft:water_bucket", 1));
        assertThat(items("minecraft:water[level=3]")).isEmpty();
    }

    @Test
    void entities() {
        StructureEntity frame = new StructureEntity(0, 0, 0, new CompoundTag().putString("id", "minecraft:item_frame")
                .put("Item", new CompoundTag().putString("id", "minecraft:diamond").putInt("count", 1)));
        assertThat(Items.forEntity(frame, false)).containsExactly(Map.entry("minecraft:item_frame", 1), Map.entry("minecraft:diamond", 1));
        StructureEntity pig = new StructureEntity(0, 0, 0, new CompoundTag().putString("id", "minecraft:pig"));
        assertThat(Items.forEntity(pig, false)).isEmpty();
        assertThat(Items.forEntity(pig, true)).containsExactly(Map.entry("minecraft:pig_spawn_egg", 1));
        StructureEntity boat = new StructureEntity(0, 0, 0, new CompoundTag().putString("id", "minecraft:oak_boat"));
        assertThat(Items.forEntity(boat, false)).containsExactly(Map.entry("minecraft:oak_boat", 1));
    }

    @Test
    void stacks() {
        assertThat(Items.stacks(40, "minecraft:stone")).isEqualTo("40");
        assertThat(Items.stacks(64, "minecraft:stone")).isEqualTo("1 stack");
        assertThat(Items.stacks(64 * 3 + 12, "minecraft:stone")).isEqualTo("3 stacks + 12");
        assertThat(Items.stacks(64 * 27 * 2 + 64, "minecraft:stone")).isEqualTo("2 shulkers + 1 stack");
        assertThat(Items.stacks(20, "minecraft:oak_sign")).isEqualTo("1 stack + 4");
        assertThat(Items.stacks(3, "minecraft:red_bed")).isEqualTo("3");
    }
}

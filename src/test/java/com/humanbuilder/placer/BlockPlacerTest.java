package com.humanbuilder.placer;

import net.minecraft.block.enums.BlockFace;
import net.minecraft.block.enums.SlabType;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationPropertyHelper;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlockPlacerTest {

    @Test
    void axisControlledBlocksOnlyUseFacesThatProduceTheRequestedAxis() {
        assertEquals(Set.of(Direction.WEST, Direction.EAST),
                Set.of(BlockPlacer.axisSupportDirections(Direction.Axis.X)));
        assertEquals(Set.of(Direction.DOWN, Direction.UP),
                Set.of(BlockPlacer.axisSupportDirections(Direction.Axis.Y)));
        assertEquals(Set.of(Direction.NORTH, Direction.SOUTH),
                Set.of(BlockPlacer.axisSupportDirections(Direction.Axis.Z)));
    }

    @Test
    void trapdoorSupportProducesTheDesiredClickedFace() {
        for (Direction facing : Direction.Type.HORIZONTAL) {
            Direction support = BlockPlacer.trapdoorSupportDirection(facing);
            assertEquals(facing, support.getOpposite());
        }
    }

    @Test
    void slabsNeverChooseAVerticalFaceThatProducesTheWrongHalf() {
        assertEquals(Set.of(Direction.NORTH, Direction.SOUTH, Direction.EAST,
                        Direction.WEST, Direction.DOWN),
                Set.of(BlockPlacer.slabSupportDirections(SlabType.BOTTOM)));
        assertEquals(Set.of(Direction.NORTH, Direction.SOUTH, Direction.EAST,
                        Direction.WEST, Direction.UP),
                Set.of(BlockPlacer.slabSupportDirections(SlabType.TOP)));
        assertEquals(Set.of(Direction.values()),
                Set.of(BlockPlacer.slabSupportDirections(SlabType.DOUBLE)));
    }

    @Test
    void faceDirectedBlocksUseTheSupportOppositeTheirDesiredFacing() {
        for (Direction facing : Direction.values()) {
            assertEquals(facing.getOpposite(),
                    BlockPlacer.faceDirectedSupportDirection(facing));
        }
    }

    @Test
    void wallMountedBlocksUseTheSupportMatchingTheirFaceState() {
        for (Direction facing : Direction.Type.HORIZONTAL) {
            assertEquals(Direction.DOWN,
                    BlockPlacer.wallMountedSupportDirection(BlockFace.FLOOR, facing));
            assertEquals(Direction.UP,
                    BlockPlacer.wallMountedSupportDirection(BlockFace.CEILING, facing));
            assertEquals(facing.getOpposite(),
                    BlockPlacer.wallMountedSupportDirection(BlockFace.WALL, facing));
        }
    }

    @Test
    void endRodAccountsForVanillaChainInversion() {
        for (Direction clickedFace : Direction.values()) {
            assertEquals(clickedFace,
                    BlockPlacer.endRodFacingAfterClick(clickedFace, false, null));
            assertEquals(clickedFace.getOpposite(),
                    BlockPlacer.endRodFacingAfterClick(
                            clickedFace, true, clickedFace));
            assertEquals(clickedFace,
                    BlockPlacer.endRodFacingAfterClick(
                            clickedFace, true, clickedFace.getOpposite()));
        }
    }

    @Test
    void standingRotationIsConvertedBackToThePlacementYaw() {
        for (float offset : new float[] {0.0f, 180.0f}) {
            for (int rotation = 0; rotation <= RotationPropertyHelper.getMax(); rotation++) {
                float yaw = BlockPlacer.rotationPlacementYaw(rotation, offset);
                assertEquals(rotation, RotationPropertyHelper.fromYaw(yaw + offset));
            }
        }
    }
}

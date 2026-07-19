package com.humanbuilder.logic;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemporarySupportPathfinderTest {

    @Test
    void buildsTheChainFromGroundTowardTheFloatingTarget() {
        BlockPos target = new BlockPos(0, 5, 0);
        List<BlockPos> path = TemporarySupportPathfinder.find(
                target,
                pos -> pos.getY() >= 1 && !pos.equals(target),
                pos -> pos.getY() == 1,
                16,
                1_000);

        assertFalse(path.isEmpty());
        assertEquals(1, path.get(0).getY(), "first support must be placeable from ground");
        assertEquals(1, manhattan(path.get(path.size() - 1), target),
                "last support must touch the schematic target");
        for (int index = 1; index < path.size(); index++) {
            assertEquals(1, manhattan(path.get(index - 1), path.get(index)),
                    "temporary supports must form one continuous chain");
        }
    }

    @Test
    void respectsTheMaximumSupportLength() {
        List<BlockPos> path = TemporarySupportPathfinder.find(
                new BlockPos(0, 20, 0),
                pos -> pos.getY() >= 1,
                pos -> pos.getY() == 1,
                4,
                1_000);

        assertTrue(path.isEmpty());
    }

    private int manhattan(BlockPos first, BlockPos second) {
        return Math.abs(first.getX() - second.getX())
                + Math.abs(first.getY() - second.getY())
                + Math.abs(first.getZ() - second.getZ());
    }
}

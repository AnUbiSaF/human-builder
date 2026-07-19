package com.humanbuilder.logic;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchitecturalPlannerTest {

    @Test
    void solidBuildingUsesReadableGlobalPhasesWithoutLosingBlocks() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                for (int z = 0; z < 5; z++) {
                    blocks.put(new BlockPos(x, y, z), null);
                }
            }
        }

        List<BuildEntry> plan = new ArchitecturalPlanner().plan(blocks, new BlockPos(-2, 0, 0));
        Set<BlockPos> unique = new HashSet<>();
        for (BuildEntry entry : plan) unique.add(entry.pos());

        assertEquals(blocks.size(), plan.size());
        assertEquals(blocks.size(), unique.size());
        assertEquals(BlockCategory.PILLAR, categoryAt(plan, new BlockPos(0, 2, 0)));
        assertEquals(BlockCategory.WALL, categoryAt(plan, new BlockPos(0, 2, 2)));
        assertEquals(BlockCategory.INTERIOR_WALL, categoryAt(plan, new BlockPos(2, 2, 2)));

        int previousStage = -1;
        int previousWorkGroup = -1;
        for (BuildEntry entry : plan) {
            int stage = stageRank(entry.category());
            assertTrue(stage >= previousStage, "construction stage moved backwards");
            assertTrue(entry.workGroup() >= previousWorkGroup, "work group moved backwards");
            previousStage = stage;
            previousWorkGroup = entry.workGroup();
        }
    }

    @Test
    void openCourtyardWallsBelongToTheExternalShell() {
        Map<BlockPos, BlockState> blocks = courtyardBuilding();
        List<BuildEntry> plan = new ArchitecturalPlanner().plan(blocks, new BlockPos(-2, 1, 4));

        long courtyardShellBlocks = plan.stream()
                .filter(entry -> entry.category() == BlockCategory.WALL)
                .filter(entry -> entry.pos().getX() == 2)
                .filter(entry -> entry.pos().getY() >= 2 && entry.pos().getY() <= 4)
                .filter(entry -> entry.pos().getZ() >= 3 && entry.pos().getZ() <= 5)
                .count();
        assertTrue(courtyardShellBlocks >= 3,
                "a minimal support column must not turn the courtyard wall into contour");
        assertEquals(BlockCategory.ROOF, categoryAt(plan, new BlockPos(1, 6, 4)));
        assertEquals(BlockCategory.FOUNDATION, categoryAt(plan, new BlockPos(1, 0, 4)));
    }

    @Test
    void deepFacadeBelowWideRoofRemainsPartOfTheShell() {
        Map<BlockPos, BlockState> blocks = coveredOpenBuilding();
        List<BuildEntry> plan = new ArchitecturalPlanner().plan(
                blocks, new BlockPos(4, 1, -3));

        assertEquals(BlockCategory.WALL,
                categoryAt(plan, new BlockPos(4, 2, 6)),
                "a wall several blocks below an overhang must not become interior fill");
    }

    @Test
    void secondLayerOfExteriorWallIsPartOfTheShell() {
        Map<BlockPos, BlockState> blocks = enclosedTwoBlockFacade();
        List<BuildEntry> plan = new ArchitecturalPlanner().plan(
                blocks, new BlockPos(4, 1, 11));

        assertEquals(BlockCategory.WALL,
                categoryAt(plan, new BlockPos(4, 2, 6)),
                "the inner half of a two-block exterior wall belongs to its shell");
    }

    @Test
    void facadeWorkFrontsNeverExceedThreeVerticalBlocks() {
        List<BuildEntry> plan = new ArchitecturalPlanner().plan(
                courtyardBuilding(), new BlockPos(-2, 1, 4));
        Map<Integer, List<BuildEntry>> wallGroups = plan.stream()
                .filter(entry -> entry.category() == BlockCategory.WALL)
                .collect(Collectors.groupingBy(BuildEntry::workGroup));

        assertTrue(!wallGroups.isEmpty());
        for (List<BuildEntry> group : wallGroups.values()) {
            int minY = group.stream().mapToInt(entry -> entry.pos().getY()).min().orElseThrow();
            int maxY = group.stream().mapToInt(entry -> entry.pos().getY()).max().orElseThrow();
            assertTrue(maxY - minY <= 2, "facade front exceeded its three-block band");
        }
    }

    @Test
    void courtyardContourHasAContinuousSupportPathToTheFoundation() {
        Map<BlockPos, BlockState> blocks = courtyardBuilding();
        List<BuildEntry> plan = new ArchitecturalPlanner().plan(
                blocks, new BlockPos(-2, 1, 4));
        Set<BlockPos> remaining = plan.stream()
                .filter(entry -> entry.category() == BlockCategory.PILLAR)
                .map(BuildEntry::pos)
                .collect(Collectors.toCollection(HashSet::new));
        Set<BlockPos> placed = plan.stream()
                .filter(entry -> entry.category() == BlockCategory.FOUNDATION)
                .map(BuildEntry::pos)
                .collect(Collectors.toCollection(HashSet::new));

        boolean madeProgress;
        do {
            madeProgress = false;
            for (BlockPos pos : List.copyOf(remaining)) {
                boolean supported = false;
                for (Direction direction : Direction.values()) {
                    supported |= placed.contains(pos.offset(direction));
                }
                if (supported) {
                    remaining.remove(pos);
                    placed.add(pos);
                    madeProgress = true;
                }
            }
        } while (madeProgress && !remaining.isEmpty());

        assertTrue(remaining.isEmpty(),
                "contour contains blocks that cannot be reached from its foundation: " + remaining);
    }

    @Test
    void nearestConnectedBuildingIsCompletedBeforeTheNextOne() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        for (int x = 0; x < 3; x++) blocks.put(new BlockPos(x, 0, 0), null);
        for (int x = 20; x < 23; x++) blocks.put(new BlockPos(x, 0, 0), null);

        List<BuildEntry> plan = new ArchitecturalPlanner().plan(blocks, BlockPos.ORIGIN);
        assertTrue(plan.subList(0, 3).stream().allMatch(entry -> entry.pos().getX() < 10));
        assertTrue(plan.subList(3, 6).stream().allMatch(entry -> entry.pos().getX() > 10));
    }

    @Test
    void configuredHeightBuildsTheCompleteStructuralBaseLayerByLayer() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 7; y++) {
                for (int z = 0; z < 4; z++) {
                    blocks.put(new BlockPos(x, y, z), null);
                }
            }
        }

        ArchitecturalPlanner planner = new ArchitecturalPlanner();
        planner.setLayeredBaseHeight(2);
        List<BuildEntry> plan = planner.plan(blocks, BlockPos.ORIGIN);
        List<BuildEntry> base = plan.stream()
                .filter(entry -> entry.category() == BlockCategory.FOUNDATION)
                .toList();

        assertEquals(4 * 4 * 2, base.size(),
                "Y=1..X must include the complete structural layers");
        int previousY = Integer.MIN_VALUE;
        for (BuildEntry entry : base) {
            assertTrue(entry.pos().getY() >= previousY,
                    "layered base returned to an earlier Y");
            previousY = entry.pos().getY();
        }
        assertTrue(plan.subList(0, base.size()).stream()
                .allMatch(entry -> entry.category() == BlockCategory.FOUNDATION),
                "planner left Y=1..X before its structural base was complete");

        int maxFirstLayerGroup = base.stream()
                .filter(entry -> entry.pos().getY() == 0)
                .mapToInt(BuildEntry::workGroup)
                .max()
                .orElseThrow();
        int minSecondLayerGroup = base.stream()
                .filter(entry -> entry.pos().getY() == 1)
                .mapToInt(BuildEntry::workGroup)
                .min()
                .orElseThrow();
        assertTrue(maxFirstLayerGroup < minSecondLayerGroup,
                "Y=2 work group opened before every Y=1 work group");
    }

    @Test
    void everyFrameWorkGroupGrowsFromItsConnectedFrontier() {
        List<BuildEntry> plan = new ArchitecturalPlanner().plan(
                courtyardBuilding(), new BlockPos(-2, 1, 4));
        Map<Integer, List<BuildEntry>> groups = plan.stream()
                .filter(entry -> entry.category() == BlockCategory.PILLAR
                        || entry.category() == BlockCategory.WALL
                        || entry.category() == BlockCategory.ROOF)
                .collect(Collectors.groupingBy(
                        BuildEntry::workGroup,
                        java.util.LinkedHashMap::new,
                        Collectors.toList()));

        for (List<BuildEntry> group : groups.values()) {
            Set<BlockPos> emitted = new HashSet<>();
            for (BuildEntry entry : group) {
                if (!emitted.isEmpty()) {
                    boolean touchesFrontier = false;
                    for (Direction direction : Direction.values()) {
                        touchesFrontier |= emitted.contains(entry.pos().offset(direction));
                    }
                    assertTrue(touchesFrontier,
                            "frame work group jumped away from its connected frontier");
                }
                emitted.add(entry.pos());
            }
        }
    }

    @Test
    void layeredBaseHeightIsClampedToTheGuiRange() {
        ArchitecturalPlanner planner = new ArchitecturalPlanner();
        planner.setLayeredBaseHeight(0);
        assertEquals(ArchitecturalPlanner.MIN_LAYERED_BASE_HEIGHT,
                planner.getLayeredBaseHeight());
        planner.setLayeredBaseHeight(99);
        assertEquals(ArchitecturalPlanner.MAX_LAYERED_BASE_HEIGHT,
                planner.getLayeredBaseHeight());
    }

    @Test
    void leavesAndEndRodsAreDeferredUntilAllStructuralBlocks() {
        assertTrue(BlockRoleClassifier.isAlwaysDecorPath("end_rod"));
        assertTrue(BlockRoleClassifier.isAlwaysDecorPath("oak_leaves"));
        assertTrue(BlockRoleClassifier.isAlwaysDecorPath("azalea_leaves"));
    }

    @Test
    void stairsUseGeometryInsteadOfAlwaysBecomingExteriorFinish() {
        assertFalse(BlockRoleClassifier.isAlwaysDecorPath("oak_stairs"));
        assertFalse(BlockRoleClassifier.isAlwaysDecorPath("stone_slab"));
    }

    private BlockCategory categoryAt(List<BuildEntry> entries, BlockPos pos) {
        return entries.stream()
                .filter(entry -> entry.pos().equals(pos))
                .findFirst()
                .orElseThrow()
                .category();
    }

    private Map<BlockPos, BlockState> courtyardBuilding() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        for (int x = 0; x <= 8; x++) {
            for (int z = 0; z <= 8; z++) {
                blocks.put(new BlockPos(x, 0, z), null);
            }
        }
        for (int y = 1; y <= 5; y++) {
            for (int coordinate = 0; coordinate <= 8; coordinate++) {
                blocks.put(new BlockPos(coordinate, y, 0), null);
                blocks.put(new BlockPos(coordinate, y, 8), null);
                blocks.put(new BlockPos(0, y, coordinate), null);
                blocks.put(new BlockPos(8, y, coordinate), null);
            }
            for (int coordinate = 2; coordinate <= 6; coordinate++) {
                blocks.put(new BlockPos(coordinate, y, 2), null);
                blocks.put(new BlockPos(coordinate, y, 6), null);
                blocks.put(new BlockPos(2, y, coordinate), null);
                blocks.put(new BlockPos(6, y, coordinate), null);
            }
        }
        for (int x = 0; x <= 8; x++) {
            for (int z = 0; z <= 8; z++) {
                boolean courtyard = x >= 3 && x <= 5 && z >= 3 && z <= 5;
                if (!courtyard) blocks.put(new BlockPos(x, 6, z), null);
            }
        }
        return blocks;
    }

    private Map<BlockPos, BlockState> coveredOpenBuilding() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        addFloorAndRoof(blocks);
        for (int x = 0; x <= 8; x++) {
            for (int y = 1; y <= 3; y++) {
                blocks.put(new BlockPos(x, y, 6), null);
            }
        }
        return blocks;
    }

    private Map<BlockPos, BlockState> enclosedTwoBlockFacade() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        addFloorAndRoof(blocks);
        for (int y = 1; y <= 3; y++) {
            for (int x = 0; x <= 8; x++) {
                blocks.put(new BlockPos(x, y, 6), null);
                blocks.put(new BlockPos(x, y, 7), null);
            }
            for (int z = 0; z <= 7; z++) {
                blocks.put(new BlockPos(0, y, z), null);
                blocks.put(new BlockPos(8, y, z), null);
            }
            for (int x = 1; x < 8; x++) {
                blocks.put(new BlockPos(x, y, 0), null);
            }
        }
        return blocks;
    }

    private void addFloorAndRoof(Map<BlockPos, BlockState> blocks) {
        for (int x = 0; x <= 8; x++) {
            for (int z = 0; z <= 8; z++) {
                blocks.put(new BlockPos(x, 0, z), null);
                blocks.put(new BlockPos(x, 4, z), null);
            }
        }
    }

    private int stageRank(BlockCategory category) {
        return switch (category) {
            case FOUNDATION, PILLAR, WALL, ROOF -> 0;
            case WINDOW -> 1;
            case INTERIOR_WALL -> 2;
            case DECOR -> 3;
        };
    }
}

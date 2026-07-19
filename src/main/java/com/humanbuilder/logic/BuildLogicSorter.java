package com.humanbuilder.logic;

import net.minecraft.block.AnvilBlock;
import net.minecraft.block.BannerBlock;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.BedBlock;
import net.minecraft.block.BellBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ButtonBlock;
import net.minecraft.block.CandleBlock;
import net.minecraft.block.CarpetBlock;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.FlowerPotBlock;
import net.minecraft.block.LadderBlock;
import net.minecraft.block.LanternBlock;
import net.minecraft.block.LeverBlock;
import net.minecraft.block.PaneBlock;
import net.minecraft.block.PressurePlateBlock;
import net.minecraft.block.SignBlock;
import net.minecraft.block.SkullBlock;
import net.minecraft.block.TorchBlock;
import net.minecraft.block.TranslucentBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.WallBannerBlock;
import net.minecraft.block.WallSignBlock;
import net.minecraft.block.WallSkullBlock;
import net.minecraft.block.WallTorchBlock;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

/** Selects one of the two supported autonomous construction orders. */
public final class BuildLogicSorter {

    private final ArchitecturalPlanner architecturalPlanner = new ArchitecturalPlanner();

    public int getArchitecturalLayeredBaseHeight() {
        return architecturalPlanner.getLayeredBaseHeight();
    }

    public void setArchitecturalLayeredBaseHeight(int height) {
        architecturalPlanner.setLayeredBaseHeight(height);
    }

    public List<BuildEntry> categorize(Map<BlockPos, BlockState> blocks) {
        if (blocks.isEmpty()) return List.of();
        Bounds bounds = Bounds.of(blocks.keySet());
        List<BuildEntry> result = new ArrayList<>(blocks.size());
        for (Map.Entry<BlockPos, BlockState> entry : blocks.entrySet()) {
            result.add(new BuildEntry(
                    entry.getKey(),
                    entry.getValue(),
                    classify(entry.getKey(), entry.getValue(), blocks, bounds)
            ));
        }
        return result;
    }

    public List<BuildEntry> sort(Map<BlockPos, BlockState> blocks, BlockPos playerPos) {
        return sort(blocks, playerPos, SortMode.ARCHITECTURAL);
    }

    public List<BuildEntry> sort(
            Map<BlockPos, BlockState> blocks,
            BlockPos playerPos,
            SortMode mode
    ) {
        if (blocks.isEmpty()) return List.of();
        return switch (mode) {
            case LAYERED -> layeredSort(blocks, playerPos);
            case ARCHITECTURAL -> architecturalPlanner.plan(blocks, playerPos);
        };
    }

    private List<BuildEntry> layeredSort(Map<BlockPos, BlockState> blocks, BlockPos start) {
        NavigableMap<Integer, EnumMap<BlockCategory, List<BuildEntry>>> layers = new TreeMap<>();
        for (BuildEntry entry : categorize(blocks)) {
            layers.computeIfAbsent(entry.pos().getY(), ignored -> new EnumMap<>(BlockCategory.class))
                    .computeIfAbsent(entry.category(), ignored -> new ArrayList<>())
                    .add(entry);
        }

        List<BuildEntry> result = new ArrayList<>(blocks.size());
        BlockPos cursor = start;
        for (EnumMap<BlockCategory, List<BuildEntry>> layer : layers.values()) {
            List<BuildEntry> structure = new ArrayList<>();
            List<BuildEntry> windows = new ArrayList<>();
            List<BuildEntry> details = new ArrayList<>();
            for (Map.Entry<BlockCategory, List<BuildEntry>> category : layer.entrySet()) {
                if (category.getKey() == BlockCategory.DECOR) details.addAll(category.getValue());
                else if (category.getKey() == BlockCategory.WINDOW) windows.addAll(category.getValue());
                else structure.addAll(category.getValue());
            }

            cursor = appendSweep(result, structure, cursor);
            cursor = appendSweep(result, windows, cursor);
            cursor = appendSweep(result, details, cursor);
        }
        return result;
    }

    private BlockPos appendSweep(List<BuildEntry> output, List<BuildEntry> entries, BlockPos start) {
        List<BuildEntry> ordered = continuousSweep(entries, start);
        output.addAll(ordered);
        return ordered.isEmpty() ? start : ordered.get(ordered.size() - 1).pos();
    }

    private List<BuildEntry> continuousSweep(List<BuildEntry> entries, BlockPos start) {
        Set<BuildEntry> remaining = new HashSet<>(entries);
        List<BuildEntry> result = new ArrayList<>(entries.size());
        BlockPos cursor = start;
        AxisStep previousStep = null;
        BlockState previousState = null;

        while (!remaining.isEmpty()) {
            BuildEntry best = null;
            double bestScore = Double.MAX_VALUE;
            for (BuildEntry candidate : remaining) {
                AxisStep step = AxisStep.between(cursor, candidate.pos());
                int distance = candidate.manhattanDistance(cursor);
                double score = distance * 80.0;
                if (distance == 1) score -= 700.0;
                if (candidate.pos().getY() == cursor.getY()) score -= 60.0;
                if (previousState != null && previousState.equals(candidate.state())) score -= 70.0;
                if (step != null && previousStep != null && step.equals(previousStep)) score -= 240.0;

                if (best == null || score < bestScore
                        || score == bestScore && stableCompare(candidate.pos(), best.pos()) < 0) {
                    best = candidate;
                    bestScore = score;
                }
            }

            AxisStep step = AxisStep.between(cursor, best.pos());
            result.add(best);
            remaining.remove(best);
            cursor = best.pos();
            if (step != null) previousStep = step;
            previousState = best.state();
        }
        return result;
    }

    private int stableCompare(BlockPos first, BlockPos second) {
        int compare = Integer.compare(first.getY(), second.getY());
        if (compare != 0) return compare;
        compare = Integer.compare(first.getZ(), second.getZ());
        if (compare != 0) return compare;
        return Integer.compare(first.getX(), second.getX());
    }

    private BlockCategory classify(
            BlockPos pos,
            BlockState state,
            Map<BlockPos, BlockState> blocks,
            Bounds bounds
    ) {
        if (isDetail(state)) return BlockCategory.DECOR;
        if (isWindow(state)) return BlockCategory.WINDOW;
        if (pos.getY() == bounds.minY()) return BlockCategory.FOUNDATION;
        if (hasHorizontalAirNeighbor(pos, blocks)) return BlockCategory.WALL;
        return BlockCategory.INTERIOR_WALL;
    }

    private boolean hasHorizontalAirNeighbor(BlockPos pos, Map<BlockPos, BlockState> blocks) {
        return !blocks.containsKey(pos.north())
                || !blocks.containsKey(pos.south())
                || !blocks.containsKey(pos.east())
                || !blocks.containsKey(pos.west());
    }

    private boolean isWindow(BlockState state) {
        Block block = state.getBlock();
        return block instanceof PaneBlock
                || block instanceof TranslucentBlock
                || block.getTranslationKey().contains("glass");
    }

    private boolean isDetail(BlockState state) {
        Block block = state.getBlock();
        return BlockRoleClassifier.isAlwaysDecor(state)
                || block instanceof TrapdoorBlock
                || block instanceof DoorBlock
                || block instanceof FenceGateBlock
                || block instanceof ButtonBlock
                || block instanceof LeverBlock
                || block instanceof PressurePlateBlock
                || block instanceof TorchBlock
                || block instanceof WallTorchBlock
                || block instanceof LadderBlock
                || block instanceof SignBlock
                || block instanceof WallSignBlock
                || block instanceof BannerBlock
                || block instanceof WallBannerBlock
                || block instanceof FlowerPotBlock
                || block instanceof CarpetBlock
                || block instanceof BedBlock
                || block instanceof SkullBlock
                || block instanceof WallSkullBlock
                || block instanceof CandleBlock
                || block instanceof BellBlock
                || block instanceof LanternBlock
                || block instanceof ChestBlock
                || block instanceof BarrelBlock
                || block instanceof AnvilBlock;
    }

    private record AxisStep(int x, int y, int z) {
        static AxisStep between(BlockPos from, BlockPos to) {
            int dx = Integer.compare(to.getX(), from.getX());
            int dy = Integer.compare(to.getY(), from.getY());
            int dz = Integer.compare(to.getZ(), from.getZ());
            int changedAxes = (dx == 0 ? 0 : 1) + (dy == 0 ? 0 : 1) + (dz == 0 ? 0 : 1);
            return changedAxes == 1 ? new AxisStep(dx, dy, dz) : null;
        }
    }

    private record Bounds(int minY) {
        static Bounds of(Set<BlockPos> positions) {
            int minY = Integer.MAX_VALUE;
            for (BlockPos pos : positions) minY = Math.min(minY, pos.getY());
            return new Bounds(minY);
        }
    }
}

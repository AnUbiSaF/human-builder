package com.humanbuilder.logic;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Finds a short air-only chain from permanent support to a build target. */
public final class TemporarySupportPathfinder {

    private static final Direction[] SEARCH_DIRECTIONS = {
        Direction.DOWN, Direction.NORTH, Direction.SOUTH,
        Direction.WEST, Direction.EAST, Direction.UP
    };

    private TemporarySupportPathfinder() {}

    public static List<BlockPos> find(
            BlockPos target,
            Predicate<BlockPos> isAvailable,
            Predicate<BlockPos> hasPermanentSupport,
            int maxLength,
            int maxVisitedNodes
    ) {
        return find(
                target, SEARCH_DIRECTIONS, isAvailable, hasPermanentSupport,
                maxLength, maxVisitedNodes);
    }

    public static List<BlockPos> find(
            BlockPos target,
            Direction[] targetSupportDirections,
            Predicate<BlockPos> isAvailable,
            Predicate<BlockPos> hasPermanentSupport,
            int maxLength,
            int maxVisitedNodes
    ) {
        List<BlockPos> vertical = findVerticalColumn(
                target, targetSupportDirections,
                isAvailable, hasPermanentSupport, maxLength);
        if (!vertical.isEmpty()) return vertical;

        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        Map<BlockPos, BlockPos> towardTarget = new HashMap<>();
        Set<BlockPos> visited = new HashSet<>();

        for (Direction direction : targetSupportDirections) {
            BlockPos start = target.offset(direction).toImmutable();
            if (isAvailable.test(start) && visited.add(start)) {
                open.addLast(start);
                towardTarget.put(start, null);
            }
        }

        int searched = 0;
        BlockPos destination = null;
        while (!open.isEmpty() && searched++ < maxVisitedNodes) {
            BlockPos current = open.removeFirst();
            if (hasPermanentSupport.test(current)) {
                destination = current;
                break;
            }
            if (manhattanDistance(current, target) >= maxLength) continue;

            for (Direction direction : SEARCH_DIRECTIONS) {
                BlockPos neighbor = current.offset(direction).toImmutable();
                if (visited.add(neighbor) && isAvailable.test(neighbor)) {
                    towardTarget.put(neighbor, current);
                    open.addLast(neighbor);
                }
            }
        }
        if (destination == null) return List.of();

        List<BlockPos> path = new ArrayList<>();
        BlockPos cursor = destination;
        while (cursor != null) {
            path.add(cursor);
            cursor = towardTarget.get(cursor);
        }
        return path;
    }

    private static List<BlockPos> findVerticalColumn(
            BlockPos target,
            Direction[] targetSupportDirections,
            Predicate<BlockPos> isAvailable,
            Predicate<BlockPos> hasPermanentSupport,
            int maxLength
    ) {
        for (Direction direction : targetSupportDirections) {
            BlockPos cursor = target.offset(direction).toImmutable();
            List<BlockPos> fromTarget = new ArrayList<>();
            for (int length = 0; length < maxLength; length++) {
                if (!isAvailable.test(cursor)) break;
                fromTarget.add(cursor);
                if (hasPermanentSupport.test(cursor)) {
                    java.util.Collections.reverse(fromTarget);
                    return fromTarget;
                }
                cursor = cursor.down().toImmutable();
            }
        }
        return List.of();
    }

    private static int manhattanDistance(BlockPos first, BlockPos second) {
        return Math.abs(first.getX() - second.getX())
                + Math.abs(first.getY() - second.getY())
                + Math.abs(first.getZ() - second.getZ());
    }
}

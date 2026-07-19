package com.humanbuilder.logic;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Predicate;

/** Spatial model used to keep structural work separate from room filling. */
final class RoomLayout {

    private static final int OUTSIDE = -1;
    private static final int UNKNOWN = -2;
    private static final int MAX_SCAN_VOLUME = 3_000_000;
    private static final int MIN_ROOM_VOLUME = 4;
    private static final int MAX_PORTAL_HALF_WIDTH = 2;
    private static final int MAX_WALL_THICKNESS = 3;
    private static final int ROOM_ASSIGNMENT_RADIUS = 4;

    private final Set<BlockPos> occupied;
    private final Bounds bounds;
    private final Predicate<BlockPos> exteriorAir;
    private final Map<BlockPos, Integer> roomByAir;
    private final Map<Integer, Set<BlockPos>> airByRoom;
    private final Map<Integer, BlockPos> centerByRoom;

    private RoomLayout(
            Set<BlockPos> occupied,
            Bounds bounds,
            Predicate<BlockPos> exteriorAir,
            Map<BlockPos, Integer> roomByAir,
            Map<Integer, Set<BlockPos>> airByRoom,
            Map<Integer, BlockPos> centerByRoom
    ) {
        this.occupied = occupied;
        this.bounds = bounds;
        this.exteriorAir = exteriorAir;
        this.roomByAir = roomByAir;
        this.airByRoom = airByRoom;
        this.centerByRoom = centerByRoom;
    }

    static RoomLayout detect(
            Set<BlockPos> positions,
            Predicate<BlockPos> exteriorAir
    ) {
        Set<BlockPos> occupied = new HashSet<>();
        for (BlockPos pos : positions) occupied.add(pos.toImmutable());
        Bounds bounds = Bounds.of(occupied);
        long volume = bounds.volume();
        if (occupied.isEmpty() || volume <= 0 || volume > MAX_SCAN_VOLUME) {
            return new RoomLayout(
                    occupied, bounds, exteriorAir, Map.of(), Map.of(), Map.of());
        }

        Set<BlockPos> interiorAir = new HashSet<>();
        for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!occupied.contains(pos) && !exteriorAir.test(pos)) {
                        interiorAir.add(pos);
                    }
                }
            }
        }

        Set<BlockPos> portals = findNarrowPortals(interiorAir, occupied);
        Set<BlockPos> unvisited = new HashSet<>(interiorAir);
        unvisited.removeAll(portals);
        Map<BlockPos, Integer> roomByAir = new HashMap<>();
        Map<Integer, Set<BlockPos>> airByRoom = new HashMap<>();
        int nextRoom = 0;

        List<BlockPos> seeds = new ArrayList<>(unvisited);
        seeds.sort(RoomLayout::compareStable);
        for (BlockPos seed : seeds) {
            if (!unvisited.remove(seed)) continue;
            Set<BlockPos> room = floodRoom(seed, unvisited);
            if (room.size() < MIN_ROOM_VOLUME) continue;
            int roomId = nextRoom++;
            airByRoom.put(roomId, room);
            for (BlockPos pos : room) roomByAir.put(pos, roomId);
        }

        for (BlockPos portal : portals) {
            Integer room = nearestAdjacentRoom(portal, roomByAir);
            if (room != null) roomByAir.put(portal, room);
        }

        Map<Integer, BlockPos> centers = new HashMap<>();
        for (Map.Entry<Integer, Set<BlockPos>> entry : airByRoom.entrySet()) {
            centers.put(entry.getKey(), center(entry.getValue()));
        }
        return new RoomLayout(
                occupied, bounds, exteriorAir, roomByAir, airByRoom, centers);
    }

    Set<Integer> roomIds() {
        return airByRoom.keySet();
    }

    Set<BlockPos> roomAir(int roomId) {
        return airByRoom.getOrDefault(roomId, Set.of());
    }

    BlockPos roomCenter(int roomId) {
        return centerByRoom.get(roomId);
    }

    OptionalInt roomForBlock(BlockPos block) {
        Set<Integer> adjacent = adjacentRooms(block);
        if (!adjacent.isEmpty()) return OptionalInt.of(nearestRoom(block, adjacent));
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = block.offset(direction);
            if (!occupied.contains(neighbor) && exteriorAir.test(neighbor)) {
                return OptionalInt.empty();
            }
        }

        for (int radius = 2; radius <= ROOM_ASSIGNMENT_RADIUS; radius++) {
            Set<Integer> nearby = new HashSet<>();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    int remaining = radius - Math.abs(dx) - Math.abs(dy);
                    if (remaining < 0) continue;
                    collectRoom(block.add(dx, dy, remaining), nearby);
                    if (remaining != 0) collectRoom(block.add(dx, dy, -remaining), nearby);
                }
            }
            if (!nearby.isEmpty()) return OptionalInt.of(nearestRoom(block, nearby));
        }
        return OptionalInt.empty();
    }

    BoundaryInfo boundaryInfo(BlockPos block) {
        EnumSet<Direction.Axis> axes = EnumSet.noneOf(Direction.Axis.class);
        Set<Integer> rooms = new HashSet<>();
        boolean touchesOutside = false;

        for (Direction.Axis axis : Direction.Axis.values()) {
            Segment segment = occupiedSegment(block, axis);
            if (segment == null || !isPlanarAt(block, axis)) continue;
            int negative = regionAt(segment.negativeOutside());
            int positive = regionAt(segment.positiveOutside());
            if (!separatesRoomRegions(negative, positive)) continue;

            axes.add(axis);
            if (negative >= 0) rooms.add(negative);
            if (positive >= 0) rooms.add(positive);
            touchesOutside |= negative == OUTSIDE || positive == OUTSIDE;
        }
        return new BoundaryInfo(axes, Set.copyOf(rooms), touchesOutside);
    }

    boolean isStructuralRun(BlockPos pos) {
        if (adjacentRooms(pos).isEmpty()) return false;
        for (Direction.Axis axis : Direction.Axis.values()) {
            int length = 1
                    + contiguousLength(pos, negative(axis), 8)
                    + contiguousLength(pos, positive(axis), 8);
            int minimumLength = axis == Direction.Axis.Y ? 4 : 3;
            if (length >= minimumLength
                    && (axis == Direction.Axis.Y || !occupied.contains(pos.down()))) {
                return true;
            }
        }
        return false;
    }

    private Segment occupiedSegment(BlockPos origin, Direction.Axis axis) {
        Direction negative = negative(axis);
        Direction positive = positive(axis);
        BlockPos first = origin;
        BlockPos last = origin;
        int length = 1;

        while (length <= MAX_WALL_THICKNESS && occupied.contains(first.offset(negative))) {
            first = first.offset(negative);
            length++;
        }
        while (length <= MAX_WALL_THICKNESS && occupied.contains(last.offset(positive))) {
            last = last.offset(positive);
            length++;
        }
        if (length > MAX_WALL_THICKNESS
                || occupied.contains(first.offset(negative))
                || occupied.contains(last.offset(positive))) {
            return null;
        }
        return new Segment(first.offset(negative), last.offset(positive));
    }

    private boolean isPlanarAt(BlockPos pos, Direction.Axis normal) {
        int neighbors = 0;
        for (Direction direction : Direction.values()) {
            if (direction.getAxis() == normal) continue;
            if (occupied.contains(pos.offset(direction))) neighbors++;
        }
        return neighbors >= 2;
    }

    private int contiguousLength(BlockPos origin, Direction direction, int limit) {
        int length = 0;
        BlockPos cursor = origin;
        while (length < limit && occupied.contains(cursor.offset(direction))) {
            cursor = cursor.offset(direction);
            length++;
        }
        return length;
    }

    private Set<Integer> adjacentRooms(BlockPos pos) {
        Set<Integer> result = new HashSet<>();
        for (Direction direction : Direction.values()) {
            Integer room = roomByAir.get(pos.offset(direction));
            if (room != null) result.add(room);
        }
        return result;
    }

    private int regionAt(BlockPos pos) {
        Integer room = roomByAir.get(pos);
        if (room != null) return room;
        if (!bounds.contains(pos) || (!occupied.contains(pos) && exteriorAir.test(pos))) {
            return OUTSIDE;
        }
        return UNKNOWN;
    }

    private boolean separatesRoomRegions(int first, int second) {
        if (first >= 0 && second >= 0) return first != second;
        return first >= 0 && second == OUTSIDE || second >= 0 && first == OUTSIDE;
    }

    private int nearestRoom(BlockPos pos, Set<Integer> candidates) {
        return candidates.stream()
                .min(Comparator
                        .comparingDouble((Integer room) ->
                                centerByRoom.get(room).getSquaredDistance(pos))
                        .thenComparingInt(Integer::intValue))
                .orElseThrow();
    }

    private void collectRoom(BlockPos pos, Set<Integer> output) {
        Integer room = roomByAir.get(pos);
        if (room != null) output.add(room);
    }

    private static Set<BlockPos> findNarrowPortals(
            Set<BlockPos> air,
            Set<BlockPos> occupied
    ) {
        Set<BlockPos> result = new HashSet<>();
        for (BlockPos pos : air) {
            if (isPortalAcross(pos, Direction.Axis.X, air, occupied)
                    || isPortalAcross(pos, Direction.Axis.Z, air, occupied)) {
                result.add(pos);
            }
        }
        return result;
    }

    private static boolean isPortalAcross(
            BlockPos pos,
            Direction.Axis constrainedAxis,
            Set<BlockPos> air,
            Set<BlockPos> occupied
    ) {
        if (!isSqueezed(pos, constrainedAxis, occupied)) return false;
        Direction forward = constrainedAxis == Direction.Axis.X
                ? Direction.NORTH : Direction.WEST;
        Direction backward = forward.getOpposite();
        BlockPos first = pos.offset(forward);
        BlockPos second = pos.offset(backward);
        if (!air.contains(first) || !air.contains(second)) return false;
        return !isSqueezed(first, constrainedAxis, occupied)
                || !isSqueezed(second, constrainedAxis, occupied);
    }

    private static boolean isSqueezed(
            BlockPos pos,
            Direction.Axis axis,
            Set<BlockPos> occupied
    ) {
        Direction negative = negative(axis);
        Direction positive = positive(axis);
        int first = distanceToOccupied(pos, negative, occupied);
        int second = distanceToOccupied(pos, positive, occupied);
        return first > 0 && second > 0
                && first + second <= MAX_PORTAL_HALF_WIDTH + 1;
    }

    private static int distanceToOccupied(
            BlockPos pos,
            Direction direction,
            Set<BlockPos> occupied
    ) {
        BlockPos cursor = pos;
        for (int distance = 1; distance <= MAX_PORTAL_HALF_WIDTH; distance++) {
            cursor = cursor.offset(direction);
            if (occupied.contains(cursor)) return distance;
        }
        return -1;
    }

    private static Set<BlockPos> floodRoom(BlockPos seed, Set<BlockPos> unvisited) {
        Set<BlockPos> room = new HashSet<>();
        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        open.addLast(seed);
        room.add(seed);
        while (!open.isEmpty()) {
            BlockPos current = open.removeFirst();
            for (Direction direction : Direction.values()) {
                BlockPos neighbor = current.offset(direction);
                if (unvisited.remove(neighbor)) {
                    room.add(neighbor);
                    open.addLast(neighbor);
                }
            }
        }
        return room;
    }

    private static Integer nearestAdjacentRoom(
            BlockPos portal,
            Map<BlockPos, Integer> roomByAir
    ) {
        Integer best = null;
        for (Direction direction : Direction.values()) {
            Integer candidate = roomByAir.get(portal.offset(direction));
            if (candidate != null && (best == null || candidate < best)) best = candidate;
        }
        return best;
    }

    private static BlockPos center(Set<BlockPos> positions) {
        long x = 0;
        long y = 0;
        long z = 0;
        for (BlockPos pos : positions) {
            x += pos.getX();
            y += pos.getY();
            z += pos.getZ();
        }
        int size = Math.max(1, positions.size());
        return new BlockPos(
                Math.round((float) x / size),
                Math.round((float) y / size),
                Math.round((float) z / size));
    }

    private static int compareStable(BlockPos first, BlockPos second) {
        int compare = Integer.compare(first.getY(), second.getY());
        if (compare != 0) return compare;
        compare = Integer.compare(first.getZ(), second.getZ());
        return compare != 0 ? compare : Integer.compare(first.getX(), second.getX());
    }

    private static Direction negative(Direction.Axis axis) {
        return switch (axis) {
            case X -> Direction.WEST;
            case Y -> Direction.DOWN;
            case Z -> Direction.NORTH;
        };
    }

    private static Direction positive(Direction.Axis axis) {
        return negative(axis).getOpposite();
    }

    record BoundaryInfo(
            EnumSet<Direction.Axis> axes,
            Set<Integer> rooms,
            boolean touchesOutside
    ) {
        boolean separatesRooms() {
            return rooms.size() >= 2;
        }
    }

    private record Segment(BlockPos negativeOutside, BlockPos positiveOutside) {}

    private record Bounds(
            int minX,
            int maxX,
            int minY,
            int maxY,
            int minZ,
            int maxZ
    ) {
        static Bounds of(Set<BlockPos> positions) {
            if (positions.isEmpty()) return new Bounds(0, -1, 0, -1, 0, -1);
            int minX = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int minY = Integer.MAX_VALUE;
            int maxY = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxZ = Integer.MIN_VALUE;
            for (BlockPos pos : positions) {
                minX = Math.min(minX, pos.getX());
                maxX = Math.max(maxX, pos.getX());
                minY = Math.min(minY, pos.getY());
                maxY = Math.max(maxY, pos.getY());
                minZ = Math.min(minZ, pos.getZ());
                maxZ = Math.max(maxZ, pos.getZ());
            }
            return new Bounds(minX, maxX, minY, maxY, minZ, maxZ);
        }

        boolean contains(BlockPos pos) {
            return pos.getX() >= minX && pos.getX() <= maxX
                    && pos.getY() >= minY && pos.getY() <= maxY
                    && pos.getZ() >= minZ && pos.getZ() <= maxZ;
        }

        long volume() {
            if (maxX < minX || maxY < minY || maxZ < minZ) return 0;
            return (long) (maxX - minX + 1)
                    * (maxY - minY + 1)
                    * (maxZ - minZ + 1);
        }
    }
}

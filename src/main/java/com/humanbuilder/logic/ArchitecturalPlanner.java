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
import net.minecraft.block.ChainBlock;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceBlock;
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
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.WallBannerBlock;
import net.minecraft.block.WallBlock;
import net.minecraft.block.WallSignBlock;
import net.minecraft.block.WallSkullBlock;
import net.minecraft.block.WallTorchBlock;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Geometry-first planner for replay-friendly construction. A configurable
 * number of base levels is completed layer by layer before connected phases.
 */
public final class ArchitecturalPlanner {

    public static final int MIN_LAYERED_BASE_HEIGHT = 1;
    public static final int MAX_LAYERED_BASE_HEIGHT = 8;
    public static final int DEFAULT_LAYERED_BASE_HEIGHT = 1;
    private static final int MAX_EXTERIOR_WALL_DEPTH = 1;
    private static final int EXTERIOR_PORTAL_RADIUS = 2;
    private static final int EXTERIOR_SURFACE_REACH = 3;
    private static final int MAX_DIRECT_EXTERIOR_DEPTH = 4;

    private int layeredBaseHeight = DEFAULT_LAYERED_BASE_HEIGHT;

    private enum Phase {
        CONTOUR,
        OPAQUE_SHELL,
        ROOF,
        EXTERIOR_FINISH,
        INTERIOR_STRUCTURE,
        INTERIOR_DETAIL
    }

    public int getLayeredBaseHeight() {
        return layeredBaseHeight;
    }

    public void setLayeredBaseHeight(int layeredBaseHeight) {
        this.layeredBaseHeight = Math.max(
                MIN_LAYERED_BASE_HEIGHT,
                Math.min(MAX_LAYERED_BASE_HEIGHT, layeredBaseHeight));
    }

    boolean isExteriorAir(Set<BlockPos> component, BlockPos air) {
        if (component.isEmpty()) return true;
        return OutdoorAirIndex.create(component, Bounds.of(component)).isOutdoorAir(air);
    }

    RoomLayout analyzeRooms(Set<BlockPos> component) {
        if (component.isEmpty()) {
            return RoomLayout.detect(Set.of(), ignored -> true);
        }
        OutdoorAirIndex outdoor = OutdoorAirIndex.create(
                component, Bounds.of(component));
        return RoomLayout.detect(component, outdoor::isOutdoorAir);
    }

    public List<BuildEntry> plan(Map<BlockPos, BlockState> blocks, BlockPos start) {
        if (blocks.isEmpty()) return List.of();

        Map<BlockPos, BlockState> immutableBlocks = new HashMap<>(blocks.size());
        for (Map.Entry<BlockPos, BlockState> entry : blocks.entrySet()) {
            immutableBlocks.put(entry.getKey().toImmutable(), entry.getValue());
        }

        Bounds globalBounds = Bounds.of(immutableBlocks.keySet());
        Map<BlockPos, Phase> phaseByPosition = new HashMap<>();
        for (Set<BlockPos> component : connectedComponents(immutableBlocks.keySet())) {
            EnumMap<Phase, Set<BlockPos>> classified = classifyComponent(
                    component, immutableBlocks);
            for (Phase phase : Phase.values()) {
                for (BlockPos pos : classified.get(phase)) {
                    phaseByPosition.put(pos, phase);
                }
            }
        }

        RoomLayout rooms = analyzeRooms(immutableBlocks.keySet());
        promoteConnectedExteriorFinish(
                phaseByPosition, immutableBlocks, rooms);

        Map<BlockPos, RoomLayout.BoundaryInfo> boundaries = new HashMap<>();
        Set<BlockPos> skeleton = new HashSet<>();
        for (BlockPos pos : immutableBlocks.keySet()) {
            Phase phase = phaseByPosition.get(pos);
            BlockState state = immutableBlocks.get(pos);
            RoomLayout.BoundaryInfo boundary = rooms.boundaryInfo(pos);
            boundaries.put(pos, boundary);

            boolean frame = phase == Phase.CONTOUR
                    || phase == Phase.OPAQUE_SHELL
                    || phase == Phase.ROOF;
            boolean interiorStructure = phase == Phase.INTERIOR_STRUCTURE
                    && (!boundary.axes().isEmpty() || rooms.isStructuralRun(pos));
            boolean glassPartition = isGlass(state) && boundary.separatesRooms();
            if (frame || interiorStructure || glassPartition) skeleton.add(pos);
        }

        List<BuildEntry> result = new ArrayList<>(blocks.size());
        Set<BlockPos> planned = new HashSet<>();
        BlockPos cursor = start;
        int workGroup = 1;
        int maxLocalY = globalBounds.maxY() - globalBounds.minY() + 1;

        // Preserve the configured strict base, then switch from global Y bands
        // to complete architectural surfaces.
        for (int localY = 1;
             localY <= Math.min(layeredBaseHeight, maxLocalY);
             localY++) {
            Set<BlockPos> layer = new HashSet<>();
            for (BlockPos pos : immutableBlocks.keySet()) {
                Phase phase = phaseByPosition.get(pos);
                if (localY(pos, globalBounds) == localY
                        && (skeleton.contains(pos) || phase == Phase.INTERIOR_STRUCTURE)) {
                    layer.add(pos);
                }
            }
            List<List<BlockPos>> groups = connectedWorkGroups(
                    layer, immutableBlocks.keySet(), planned,
                    immutableBlocks, globalBounds, cursor, phaseByPosition);
            for (List<BlockPos> group : groups) {
                for (BlockPos pos : group) {
                    result.add(new BuildEntry(
                            pos, immutableBlocks.get(pos),
                            BlockCategory.FOUNDATION, workGroup));
                    planned.add(pos);
                }
                cursor = group.get(group.size() - 1);
                workGroup++;
            }
        }

        Set<BlockPos> contour = new HashSet<>();
        for (BlockPos pos : skeleton) {
            if (!planned.contains(pos) && phaseByPosition.get(pos) == Phase.CONTOUR) {
                contour.add(pos);
            }
        }
        for (List<BlockPos> group : connectedWorkGroups(
                contour, immutableBlocks.keySet(), planned,
                immutableBlocks, globalBounds, cursor, phaseByPosition)) {
            for (BlockPos pos : group) {
                result.add(new BuildEntry(
                        pos, immutableBlocks.get(pos), BlockCategory.PILLAR, workGroup));
                planned.add(pos);
            }
            cursor = group.get(group.size() - 1);
            workGroup++;
        }

        Set<BlockPos> structuralSurfaces = new HashSet<>(skeleton);
        structuralSurfaces.removeAll(planned);
        List<AtomicGroup> surfaceGroups = structuralSurfaceGroups(
                structuralSurfaces, phaseByPosition, boundaries,
                immutableBlocks.keySet());
        for (List<BlockPos> group : orderAtomicGroups(
                surfaceGroups, immutableBlocks.keySet(), planned,
                immutableBlocks, globalBounds, cursor, phaseByPosition)) {
            for (BlockPos pos : group) {
                BlockCategory category = phaseByPosition.get(pos) == Phase.ROOF
                        ? BlockCategory.ROOF : BlockCategory.WALL;
                result.add(new BuildEntry(
                        pos, immutableBlocks.get(pos), category, workGroup));
                planned.add(pos);
            }
            cursor = group.get(group.size() - 1);
            workGroup++;
        }

        Set<BlockPos> exteriorFinish = new HashSet<>();
        for (Map.Entry<BlockPos, Phase> entry : phaseByPosition.entrySet()) {
            if (entry.getValue() == Phase.EXTERIOR_FINISH
                    && !planned.contains(entry.getKey())) {
                exteriorFinish.add(entry.getKey());
            }
        }
        for (List<BlockPos> group : orderFacadeElements(
                exteriorFinish, immutableBlocks.keySet(), planned,
                immutableBlocks, cursor)) {
            for (BlockPos pos : group) {
                result.add(new BuildEntry(
                        pos, immutableBlocks.get(pos), BlockCategory.WINDOW, workGroup));
                planned.add(pos);
            }
            cursor = group.get(group.size() - 1);
            workGroup++;
        }

        Map<Integer, Set<BlockPos>> workByRoom = new HashMap<>();
        Set<BlockPos> unassigned = new HashSet<>();
        for (BlockPos pos : immutableBlocks.keySet()) {
            if (planned.contains(pos)) continue;
            java.util.OptionalInt room = rooms.roomForBlock(pos);
            if (room.isPresent()) {
                workByRoom.computeIfAbsent(room.getAsInt(), ignored -> new HashSet<>())
                        .add(pos);
            } else {
                unassigned.add(pos);
            }
        }

        while (!workByRoom.isEmpty()) {
            BlockPos selectionOrigin = cursor;
            int roomId = workByRoom.entrySet().stream()
                    .min(Comparator
                            .comparingDouble((Map.Entry<Integer, Set<BlockPos>> entry) ->
                                    distanceToComponent(entry.getValue(), selectionOrigin))
                            .thenComparingInt(Map.Entry::getKey))
                    .orElseThrow()
                    .getKey();
            Set<BlockPos> roomWork = workByRoom.remove(roomId);

            for (Phase localPhase : List.of(
                    Phase.INTERIOR_STRUCTURE, Phase.INTERIOR_DETAIL)) {
                Set<BlockPos> positions = new HashSet<>();
                for (BlockPos pos : roomWork) {
                    if (phaseByPosition.get(pos) == localPhase
                            || localPhase == Phase.INTERIOR_DETAIL
                            && phaseByPosition.get(pos) != Phase.INTERIOR_STRUCTURE) {
                        positions.add(pos);
                    }
                }
                roomWork.removeAll(positions);
                for (List<BlockPos> group : connectedWorkGroups(
                        positions, immutableBlocks.keySet(), planned,
                        immutableBlocks, globalBounds, cursor, phaseByPosition)) {
                    for (BlockPos pos : group) {
                        result.add(new BuildEntry(
                                pos, immutableBlocks.get(pos),
                                categoryFor(phaseByPosition.get(pos)), workGroup));
                        planned.add(pos);
                    }
                    cursor = group.get(group.size() - 1);
                    workGroup++;
                }
            }
            unassigned.addAll(roomWork);
        }

        for (List<BlockPos> group : connectedWorkGroups(
                unassigned, immutableBlocks.keySet(), planned,
                immutableBlocks, globalBounds, cursor, phaseByPosition)) {
            for (BlockPos pos : group) {
                result.add(new BuildEntry(
                        pos, immutableBlocks.get(pos),
                        categoryFor(phaseByPosition.get(pos)), workGroup));
                planned.add(pos);
            }
            cursor = group.get(group.size() - 1);
            workGroup++;
        }
        return result;
    }

    private EnumMap<Phase, Set<BlockPos>> classifyComponent(
            Set<BlockPos> component,
            Map<BlockPos, BlockState> blocks
    ) {
        Bounds bounds = Bounds.of(component);
        OutdoorAirIndex outdoor = OutdoorAirIndex.create(component, bounds);
        Set<BlockPos> roof = findRoof(component, blocks, outdoor, bounds);
        Map<BlockPos, Set<Direction>> outdoorFacesByPosition = new HashMap<>();
        for (BlockPos pos : component) {
            outdoorFacesByPosition.put(pos, outdoor.horizontalFaces(pos));
        }
        Set<BlockPos> opaqueShell = findOpaqueShell(
                component, blocks, outdoorFacesByPosition);
        EnumMap<Phase, Set<BlockPos>> positionsByPhase = new EnumMap<>(Phase.class);
        for (Phase phase : Phase.values()) positionsByPhase.put(phase, new HashSet<>());

        for (BlockPos pos : component) {
            BlockState state = blocks.get(pos);
            Set<Direction> outdoorFaces = outdoorFacesByPosition.get(pos);
            boolean exteriorVisible = !outdoorFaces.isEmpty() || outdoor.isOutdoorAir(pos.up());
            Phase phase;

            if (isAlwaysDecor(state)) {
                phase = exteriorVisible
                        ? Phase.EXTERIOR_FINISH
                        : Phase.INTERIOR_DETAIL;
            } else if (isContourEligible(state)
                    && isContour(pos, component, outdoor, outdoorFaces, bounds)) {
                phase = Phase.CONTOUR;
            } else if (roof.contains(pos) && !isPureDetail(state) && !isGlass(state)) {
                phase = Phase.ROOF;
            } else if (opaqueShell.contains(pos)) {
                phase = Phase.OPAQUE_SHELL;
            } else if (!outdoorFaces.isEmpty()) {
                phase = isExteriorFinishElement(state)
                        ? Phase.EXTERIOR_FINISH
                        : Phase.OPAQUE_SHELL;
            } else if (exteriorVisible && isExteriorFinishElement(state)) {
                phase = Phase.EXTERIOR_FINISH;
            } else if (isInteriorDetail(state)) {
                phase = Phase.INTERIOR_DETAIL;
            } else {
                phase = Phase.INTERIOR_STRUCTURE;
            }
            positionsByPhase.get(phase).add(pos);
        }
        connectContourToFoundation(positionsByPhase, component, blocks, bounds);
        return positionsByPhase;
    }

    private Set<BlockPos> findOpaqueShell(
            Set<BlockPos> component,
            Map<BlockPos, BlockState> blocks,
            Map<BlockPos, Set<Direction>> outdoorFacesByPosition
    ) {
        Set<BlockPos> shell = new HashSet<>();
        for (BlockPos surface : component) {
            for (Direction exteriorFace : outdoorFacesByPosition.get(surface)) {
                BlockPos cursor = surface;
                int structuralLayers = 0;
                while (component.contains(cursor)) {
                    if (isContourEligible(blocks.get(cursor))) {
                        shell.add(cursor);
                        structuralLayers++;
                        if (structuralLayers > MAX_EXTERIOR_WALL_DEPTH) break;
                    } else if (!cursor.equals(surface)) {
                        break;
                    }
                    cursor = cursor.offset(exteriorFace.getOpposite());
                }
            }
        }
        return shell;
    }

    private void promoteConnectedExteriorFinish(
            Map<BlockPos, Phase> phaseByPosition,
            Map<BlockPos, BlockState> blocks,
            RoomLayout rooms
    ) {
        Set<BlockPos> finishCandidates = new HashSet<>();
        for (Map.Entry<BlockPos, BlockState> entry : blocks.entrySet()) {
            if (isExteriorFinishElement(entry.getValue())
                    || isAlwaysDecor(entry.getValue())) {
                finishCandidates.add(entry.getKey());
            }
        }

        Set<BlockPos> exteriorSeeds = new HashSet<>();
        for (BlockPos pos : finishCandidates) {
            if (phaseByPosition.get(pos) == Phase.EXTERIOR_FINISH
                    || rooms.boundaryInfo(pos).touchesOutside()) {
                exteriorSeeds.add(pos);
            }
        }
        for (BlockPos pos : expandConnectedElements(finishCandidates, exteriorSeeds)) {
            phaseByPosition.put(pos, Phase.EXTERIOR_FINISH);
        }
    }

    Set<BlockPos> expandConnectedElements(
            Set<BlockPos> candidates,
            Set<BlockPos> seeds
    ) {
        Set<BlockPos> result = new HashSet<>();
        for (Set<BlockPos> element : splitComponents(candidates, true)) {
            if (element.stream().anyMatch(seeds::contains)) result.addAll(element);
        }
        return result;
    }

    private List<AtomicGroup> structuralSurfaceGroups(
            Set<BlockPos> positions,
            Map<BlockPos, Phase> phaseByPosition,
            Map<BlockPos, RoomLayout.BoundaryInfo> boundaries,
            Set<BlockPos> occupied
    ) {
        Map<SurfaceKey, Set<BlockPos>> bySurface = new HashMap<>();
        for (BlockPos pos : positions) {
            SurfaceKey key = surfaceKey(
                    pos, phaseByPosition.get(pos), boundaries.get(pos), occupied);
            bySurface.computeIfAbsent(key, ignored -> new HashSet<>()).add(pos);
        }

        List<AtomicGroup> result = new ArrayList<>();
        for (Map.Entry<SurfaceKey, Set<BlockPos>> entry : bySurface.entrySet()) {
            int rank = entry.getKey().kind() == SurfaceKind.HORIZONTAL ? 1 : 0;
            if (entry.getKey().kind() != SurfaceKind.NETWORK) {
                // Openings and already-built contour columns can disconnect a
                // wall geometrically, but they must not split the timelapse job.
                result.add(new AtomicGroup(Set.copyOf(entry.getValue()), rank));
                continue;
            }
            for (Set<BlockPos> component : splitComponents(entry.getValue(), true)) {
                result.add(new AtomicGroup(component, rank));
            }
        }
        return result;
    }

    private SurfaceKey surfaceKey(
            BlockPos pos,
            Phase phase,
            RoomLayout.BoundaryInfo boundary,
            Set<BlockPos> occupied
    ) {
        EnumSet<Direction.Axis> axes = boundary == null
                ? EnumSet.noneOf(Direction.Axis.class)
                : boundary.axes();
        if (phase == Phase.ROOF || axes.contains(Direction.Axis.Y)) {
            return new SurfaceKey(SurfaceKind.HORIZONTAL, pos.getY());
        }
        if (axes.size() == 1 && axes.contains(Direction.Axis.X)) {
            return new SurfaceKey(SurfaceKind.VERTICAL_X, pos.getX());
        }
        if (axes.size() == 1 && axes.contains(Direction.Axis.Z)) {
            return new SurfaceKey(SurfaceKind.VERTICAL_Z, pos.getZ());
        }

        boolean exposedX = !occupied.contains(pos.west()) || !occupied.contains(pos.east());
        boolean exposedZ = !occupied.contains(pos.north()) || !occupied.contains(pos.south());
        if (exposedX && !exposedZ) {
            return new SurfaceKey(SurfaceKind.VERTICAL_X, pos.getX());
        }
        if (exposedZ && !exposedX) {
            return new SurfaceKey(SurfaceKind.VERTICAL_Z, pos.getZ());
        }
        return new SurfaceKey(SurfaceKind.NETWORK, 0);
    }

    private List<List<BlockPos>> orderAtomicGroups(
            List<AtomicGroup> groups,
            Set<BlockPos> component,
            Set<BlockPos> planned,
            Map<BlockPos, BlockState> blocks,
            Bounds bounds,
            BlockPos start,
            Map<BlockPos, Phase> phaseByPosition
    ) {
        List<AtomicGroup> remaining = new ArrayList<>(groups);
        List<List<BlockPos>> result = new ArrayList<>();
        Set<BlockPos> emitted = new HashSet<>(planned);
        BlockPos cursor = start;

        while (!remaining.isEmpty()) {
            int rank = remaining.stream().mapToInt(AtomicGroup::rank).min().orElseThrow();
            List<AtomicGroup> ranked = remaining.stream()
                    .filter(group -> group.rank() == rank)
                    .toList();
            List<AtomicGroup> supported = ranked.stream()
                    .filter(group -> group.positions().stream().anyMatch(pos ->
                            isSupportedByPlan(
                                    pos, component, emitted, Set.of(), blocks, bounds.minY())))
                    .toList();
            List<AtomicGroup> candidates = supported.isEmpty() ? ranked : supported;
            BlockPos selectionOrigin = cursor;
            AtomicGroup next = candidates.stream()
                    .min(Comparator
                            .comparingDouble((AtomicGroup group) ->
                                    distanceToComponent(group.positions(), selectionOrigin))
                            .thenComparingInt(group -> -group.positions().size()))
                    .orElseThrow();
            remaining.remove(next);

            List<BlockPos> ordered = orderSupportAware(
                    next.positions(), component, emitted, blocks,
                    bounds, cursor, phaseByPosition);
            result.add(ordered);
            emitted.addAll(ordered);
            cursor = ordered.get(ordered.size() - 1);
        }
        return result;
    }

    private int localY(BlockPos pos, Bounds bounds) {
        return pos.getY() - bounds.minY() + 1;
    }

    private List<List<BlockPos>> workGroupsForPhase(
            Set<BlockPos> positions,
            Set<BlockPos> component,
            Set<BlockPos> planned,
            Map<BlockPos, BlockState> blocks,
            Bounds bounds,
            BlockPos start
    ) {
        if (positions.isEmpty()) return List.of();
        return connectedWorkGroups(
                positions, component, planned, blocks, bounds, start);
    }

    List<List<BlockPos>> orderFacadeElements(
            Set<BlockPos> positions,
            Set<BlockPos> component,
            Set<BlockPos> planned,
            Map<BlockPos, BlockState> blocks,
            BlockPos start
    ) {
        if (positions.isEmpty() || component.isEmpty()) return List.of();
        return workGroupsForPhase(
                positions, component, planned, blocks, Bounds.of(component), start);
    }

    /**
     * Every disconnected outline needs at least one physical route to the
     * foundation. This is especially important for courtyard walls and top
     * beams whose footing was initially classified as interior structure.
     */
    private void connectContourToFoundation(
            EnumMap<Phase, Set<BlockPos>> positionsByPhase,
            Set<BlockPos> component,
            Map<BlockPos, BlockState> blocks,
            Bounds bounds
    ) {
        Set<BlockPos> contour = positionsByPhase.get(Phase.CONTOUR);
        if (contour.isEmpty()) return;

        int attemptsRemaining = component.size();
        while (attemptsRemaining-- > 0) {
            Set<BlockPos> grounded = groundedContour(contour, bounds.minY());
            if (grounded.size() == contour.size()) return;

            Set<BlockPos> disconnected = contour.stream()
                    .filter(pos -> !grounded.contains(pos))
                    .collect(java.util.stream.Collectors.toSet());
            List<Set<BlockPos>> islands = splitComponents(disconnected, true);
            if (islands.isEmpty()) return;
            islands.sort(Comparator
                    .comparingInt((Set<BlockPos> island) -> island.stream()
                            .mapToInt(BlockPos::getY).min().orElse(Integer.MAX_VALUE))
                    .thenComparingInt(island -> island.stream()
                            .mapToInt(BlockPos::getX).min().orElse(Integer.MAX_VALUE))
                    .thenComparingInt(island -> island.stream()
                            .mapToInt(BlockPos::getZ).min().orElse(Integer.MAX_VALUE)));

            List<BlockPos> bridge = shortestVerticalBridge(
                    islands, grounded, component, blocks, bounds.minY());
            if (bridge.isEmpty()) {
                bridge = shortestStructuralBridge(
                        islands.get(0), grounded, component, blocks, bounds.minY());
            }
            if (bridge.isEmpty()) return;

            for (BlockPos pos : bridge) {
                for (Phase phase : Phase.values()) {
                    positionsByPhase.get(phase).remove(pos);
                }
                contour.add(pos);
            }
        }
    }

    private Set<BlockPos> groundedContour(Set<BlockPos> contour, int minY) {
        Set<BlockPos> grounded = new HashSet<>();
        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        for (BlockPos pos : contour) {
            if (pos.getY() == minY && grounded.add(pos)) open.addLast(pos);
        }
        while (!open.isEmpty()) {
            BlockPos current = open.removeFirst();
            for (Direction direction : Direction.values()) {
                BlockPos neighbor = current.offset(direction);
                if (contour.contains(neighbor) && grounded.add(neighbor)) {
                    open.addLast(neighbor);
                }
            }
        }
        return grounded;
    }

    private List<BlockPos> shortestVerticalBridge(
            List<Set<BlockPos>> islands,
            Set<BlockPos> grounded,
            Set<BlockPos> component,
            Map<BlockPos, BlockState> blocks,
            int minY
    ) {
        List<BlockPos> best = List.of();
        List<BlockPos> anchors = islands.stream()
                .flatMap(Collection::stream)
                .sorted(Comparator.comparingInt(BlockPos::getY)
                        .thenComparingInt(BlockPos::getX)
                        .thenComparingInt(BlockPos::getZ))
                .toList();
        for (BlockPos anchor : anchors) {
            List<BlockPos> candidate = new ArrayList<>();
            BlockPos cursor = anchor.down();
            while (cursor.getY() >= minY
                    && component.contains(cursor)
                    && isStructuralBridgeBlock(blocks.get(cursor))) {
                if (!grounded.contains(cursor)) candidate.add(cursor);
                if (cursor.getY() == minY || grounded.contains(cursor)) {
                    if (best.isEmpty() || candidate.size() < best.size()) {
                        best = List.copyOf(candidate);
                    }
                    break;
                }
                cursor = cursor.down();
            }
        }
        return best;
    }

    private List<BlockPos> shortestStructuralBridge(
            Set<BlockPos> island,
            Set<BlockPos> grounded,
            Set<BlockPos> component,
            Map<BlockPos, BlockState> blocks,
            int minY
    ) {
        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        Map<BlockPos, BlockPos> previous = new HashMap<>();
        Set<BlockPos> visited = new HashSet<>();
        List<BlockPos> starts = island.stream()
                .sorted(Comparator.comparingInt(BlockPos::getY)
                        .thenComparingInt(BlockPos::getX)
                        .thenComparingInt(BlockPos::getZ))
                .toList();
        for (BlockPos start : starts) {
            open.addLast(start);
            visited.add(start);
        }

        BlockPos destination = null;
        while (!open.isEmpty() && destination == null) {
            BlockPos current = open.removeFirst();
            for (Direction direction : Direction.values()) {
                BlockPos neighbor = current.offset(direction);
                if (!component.contains(neighbor)
                        || !isStructuralBridgeBlock(blocks.get(neighbor))
                        || !visited.add(neighbor)) {
                    continue;
                }
                previous.put(neighbor, current);
                if (grounded.contains(neighbor) || neighbor.getY() == minY) {
                    destination = neighbor;
                    break;
                }
                open.addLast(neighbor);
            }
        }
        if (destination == null) return List.of();

        List<BlockPos> bridge = new ArrayList<>();
        BlockPos cursor = destination;
        while (cursor != null && !island.contains(cursor)) {
            if (!grounded.contains(cursor)) bridge.add(cursor);
            cursor = previous.get(cursor);
        }
        return bridge;
    }

    private boolean isStructuralBridgeBlock(BlockState state) {
        return isContourEligible(state) && !isPureDetail(state);
    }

    private List<List<BlockPos>> connectedWorkGroups(
            Set<BlockPos> positions,
            Set<BlockPos> component,
            Set<BlockPos> planned,
            Map<BlockPos, BlockState> blocks,
            Bounds bounds,
            BlockPos start
    ) {
        return connectedWorkGroups(
                positions, component, planned, blocks, bounds, start, Map.of());
    }

    private List<List<BlockPos>> connectedWorkGroups(
            Set<BlockPos> positions,
            Set<BlockPos> component,
            Set<BlockPos> planned,
            Map<BlockPos, BlockState> blocks,
            Bounds bounds,
            BlockPos start,
            Map<BlockPos, Phase> phaseByPosition
    ) {
        List<Set<BlockPos>> remaining = splitComponents(positions, true);
        List<List<BlockPos>> result = new ArrayList<>();
        Set<BlockPos> emitted = new HashSet<>(planned);
        BlockPos cursor = start;

        while (!remaining.isEmpty()) {
            List<Set<BlockPos>> supported = remaining.stream()
                    .filter(front -> front.stream().anyMatch(pos -> isSupportedByPlan(
                            pos, component, emitted, Set.of(), blocks, bounds.minY())))
                    .toList();
            List<Set<BlockPos>> candidates = supported.isEmpty() ? remaining : supported;
            BlockPos selectionOrigin = cursor;
            Set<BlockPos> next = candidates.stream()
                    .min(Comparator
                            .comparingDouble((Set<BlockPos> front) ->
                                    distanceToComponent(front, selectionOrigin))
                            .thenComparingInt(front -> -front.size()))
                    .orElseThrow();
            remaining.remove(next);

            List<BlockPos> ordered = orderSupportAware(
                    next, component, emitted, blocks, bounds, cursor, phaseByPosition);
            result.add(ordered);
            emitted.addAll(ordered);
            cursor = ordered.get(ordered.size() - 1);
        }
        return result;
    }

    private List<BlockPos> orderSupportAware(
            Collection<BlockPos> phasePositions,
            Set<BlockPos> component,
            Set<BlockPos> planned,
            Map<BlockPos, BlockState> blocks,
            Bounds bounds,
            BlockPos start,
            Map<BlockPos, Phase> phaseByPosition
    ) {
        Set<BlockPos> remaining = new HashSet<>(phasePositions);
        List<BlockPos> result = new ArrayList<>(remaining.size());
        Set<BlockPos> emitted = new HashSet<>();
        BlockPos cursor = start;
        AxisStep previousStep = null;
        BlockState previousState = null;

        while (!remaining.isEmpty()) {
            BlockPos best = null;
            double bestScore = Double.MAX_VALUE;
            int bestRank = Integer.MAX_VALUE;

            for (BlockPos candidate : remaining) {
                boolean ready = isSupportedByPlan(
                        candidate, component, planned, emitted, blocks, bounds.minY());
                boolean directContinuation = manhattanDistance(candidate, cursor) == 1;
                boolean connectedFrontier = hasAdjacentPosition(candidate, emitted);
                int rank;
                if (emitted.isEmpty()) {
                    rank = ready ? (directContinuation ? 0 : 1)
                            : (directContinuation ? 2 : 3);
                } else if (ready && directContinuation) {
                    rank = 0;
                } else if (ready && connectedFrontier) {
                    rank = 1;
                } else if (ready) {
                    rank = 2;
                } else if (directContinuation) {
                    rank = 3;
                } else if (connectedFrontier) {
                    rank = 4;
                } else {
                    rank = 5;
                }
                if (rank > bestRank) continue;

                double score = routeScore(
                        cursor, candidate, previousStep, previousState, blocks.get(candidate), bounds);
                score += phaseOrder(phaseByPosition.get(candidate)) * 25.0;
                if (best == null || rank < bestRank || score < bestScore
                        || score == bestScore && compareStable(candidate, best, bounds) < 0) {
                    best = candidate;
                    bestRank = rank;
                    bestScore = score;
                }
            }

            AxisStep step = AxisStep.between(cursor, best);
            result.add(best);
            emitted.add(best);
            remaining.remove(best);
            cursor = best;
            if (step != null) previousStep = step;
            previousState = blocks.get(best);
        }
        return result;
    }

    private int phaseOrder(Phase phase) {
        if (phase == null) return 0;
        return switch (phase) {
            case CONTOUR -> 0;
            case OPAQUE_SHELL -> 1;
            case ROOF -> 2;
            case EXTERIOR_FINISH, INTERIOR_STRUCTURE, INTERIOR_DETAIL -> 0;
        };
    }

    private int manhattanDistance(BlockPos first, BlockPos second) {
        return Math.abs(first.getX() - second.getX())
                + Math.abs(first.getY() - second.getY())
                + Math.abs(first.getZ() - second.getZ());
    }

    private boolean hasAdjacentPosition(BlockPos pos, Set<BlockPos> positions) {
        for (Direction direction : Direction.values()) {
            if (positions.contains(pos.offset(direction))) return true;
        }
        return false;
    }

    private boolean isSupportedByPlan(
            BlockPos pos,
            Set<BlockPos> component,
            Set<BlockPos> planned,
            Set<BlockPos> currentGroup,
            Map<BlockPos, BlockState> blocks,
            int minY
    ) {
        BlockState state = blocks.get(pos);
        if (state != null && state.getBlock() instanceof DoorBlock
                && "upper".equals(propertyValue(state, "half"))) {
            return planned.contains(pos.down()) || currentGroup.contains(pos.down());
        }
        if (state != null && state.getBlock() instanceof BedBlock
                && "head".equals(propertyValue(state, "part"))
                && state.contains(Properties.HORIZONTAL_FACING)) {
            BlockPos foot = pos.offset(state.get(Properties.HORIZONTAL_FACING).getOpposite());
            return planned.contains(foot) || currentGroup.contains(foot);
        }

        if (pos.getY() == minY) return true;
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = pos.offset(direction);
            if (planned.contains(neighbor) || currentGroup.contains(neighbor)) return true;
        }
        for (Direction direction : Direction.values()) {
            if (component.contains(pos.offset(direction))) return false;
        }
        return true;
    }

    private Set<BlockPos> findRoof(
            Set<BlockPos> component,
            Map<BlockPos, BlockState> blocks,
            OutdoorAirIndex outdoor,
            Bounds bounds
    ) {
        Set<BlockPos> roof = new HashSet<>();
        int height = bounds.maxY() - bounds.minY();
        int roofDepth = Math.max(2, Math.min(8, (height + 2) / 3));
        int roofFloor = Math.max(bounds.minY() + Math.min(2, Math.max(1, height)),
                bounds.maxY() - roofDepth);
        for (BlockPos pos : component) {
            BlockState state = blocks.get(pos);
            if (pos.getY() < roofFloor || isPureDetail(state) || isGlass(state)) continue;
            if (outdoor.isOutdoorAir(pos.up()) && !hasBlockAbove(pos, component, bounds.maxY())) {
                roof.add(pos);
            }
        }
        return roof;
    }

    private boolean hasBlockAbove(BlockPos pos, Set<BlockPos> component, int maxY) {
        for (int y = pos.getY() + 1; y <= maxY; y++) {
            if (component.contains(new BlockPos(pos.getX(), y, pos.getZ()))) return true;
        }
        return false;
    }

    private boolean isContour(
            BlockPos pos,
            Set<BlockPos> component,
            OutdoorAirIndex outdoor,
            Set<Direction> outdoorFaces,
            Bounds bounds
    ) {
        if (outdoorFaces.isEmpty()) return false;
        boolean xFace = outdoorFaces.contains(Direction.WEST) || outdoorFaces.contains(Direction.EAST);
        boolean zFace = outdoorFaces.contains(Direction.NORTH) || outdoorFaces.contains(Direction.SOUTH);
        boolean corner = xFace && zFace;
        Set<Direction> facesBelow = outdoor.horizontalFaces(pos.down());
        boolean base = pos.getY() == bounds.minY()
                || outdoorFaces.stream().noneMatch(facesBelow::contains);
        boolean top = !component.contains(pos.up());
        return corner || base || top;
    }

    private boolean isContourEligible(BlockState state) {
        return !isGlass(state)
                && !isExteriorFinishElement(state)
                && !isPureDetail(state);
    }

    private boolean isAlwaysDecor(BlockState state) {
        return BlockRoleClassifier.isAlwaysDecor(state);
    }

    private boolean isGlass(BlockState state) {
        if (state == null) return false;
        String id = Registries.BLOCK.getId(state.getBlock()).getPath();
        return state.getBlock() instanceof PaneBlock || id.contains("glass");
    }

    private boolean isExteriorFinishElement(BlockState state) {
        if (state == null) return false;
        Block block = state.getBlock();
        return isGlass(state)
                || block instanceof TrapdoorBlock
                || block instanceof DoorBlock
                || block instanceof FenceGateBlock
                || block instanceof FenceBlock
                || block instanceof WallBlock
                || block instanceof ButtonBlock
                || block instanceof LeverBlock
                || block instanceof TorchBlock
                || block instanceof WallTorchBlock
                || block instanceof LadderBlock
                || block instanceof SignBlock
                || block instanceof WallSignBlock
                || block instanceof BannerBlock
                || block instanceof WallBannerBlock
                || block instanceof LanternBlock
                || block instanceof ChainBlock
                || block instanceof BellBlock;
    }

    private boolean isInteriorDetail(BlockState state) {
        return isExteriorFinishElement(state) || isPureDetail(state);
    }

    private boolean isPureDetail(BlockState state) {
        if (state == null) return false;
        Block block = state.getBlock();
        return isAlwaysDecor(state)
                || block instanceof TrapdoorBlock
                || block instanceof DoorBlock
                || block instanceof FenceGateBlock
                || block instanceof FenceBlock
                || block instanceof WallBlock
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
                || block instanceof ChainBlock
                || block instanceof ChestBlock
                || block instanceof BarrelBlock
                || block instanceof AnvilBlock;
    }

    private String propertyValue(BlockState state, String name) {
        for (Map.Entry<Property<?>, Comparable<?>> entry : state.getEntries().entrySet()) {
            if (entry.getKey().getName().equals(name)) return entry.getValue().toString();
        }
        return "";
    }

    private BlockCategory categoryFor(Phase phase) {
        return switch (phase) {
            case CONTOUR -> BlockCategory.PILLAR;
            case OPAQUE_SHELL -> BlockCategory.WALL;
            case ROOF -> BlockCategory.ROOF;
            case EXTERIOR_FINISH -> BlockCategory.WINDOW;
            case INTERIOR_STRUCTURE -> BlockCategory.INTERIOR_WALL;
            case INTERIOR_DETAIL -> BlockCategory.DECOR;
        };
    }

    private double routeScore(
            BlockPos from,
            BlockPos candidate,
            AxisStep previousStep,
            BlockState previousState,
            BlockState candidateState,
            Bounds bounds
    ) {
        int dx = Math.abs(candidate.getX() - from.getX());
        int dy = Math.abs(candidate.getY() - from.getY());
        int dz = Math.abs(candidate.getZ() - from.getZ());
        int manhattan = dx + dy + dz;
        double score = manhattan * 90.0 + dy * 35.0;
        if (manhattan == 1) score -= 900.0;
        if (candidate.getY() == from.getY()) score -= 70.0;
        if (previousState != null && previousState.equals(candidateState)) score -= 85.0;

        AxisStep step = AxisStep.between(from, candidate);
        if (step != null && previousStep != null) {
            if (step.equals(previousStep)) score -= 320.0;
            else if (step.isOpposite(previousStep)) score += 140.0;
        }

        int row = candidate.getZ() - bounds.minZ();
        int scanX = Math.floorMod(row, 2) == 0
                ? candidate.getX() - bounds.minX()
                : bounds.maxX() - candidate.getX();
        score += (candidate.getY() - bounds.minY()) * 0.01;
        score += row * 0.0001 + scanX * 0.000001;
        return score;
    }

    private int compareStable(BlockPos first, BlockPos second, Bounds bounds) {
        int compare = Integer.compare(first.getY(), second.getY());
        if (compare != 0) return compare;
        compare = Integer.compare(first.getZ(), second.getZ());
        if (compare != 0) return compare;
        boolean reverse = Math.floorMod(first.getZ() - bounds.minZ(), 2) != 0;
        return reverse
                ? Integer.compare(second.getX(), first.getX())
                : Integer.compare(first.getX(), second.getX());
    }

    private List<Set<BlockPos>> connectedComponents(Set<BlockPos> positions) {
        return splitComponents(positions, true);
    }

    private List<Set<BlockPos>> splitComponents(Set<BlockPos> positions, boolean vertical) {
        Set<BlockPos> unvisited = new HashSet<>(positions);
        List<Set<BlockPos>> components = new ArrayList<>();
        while (!unvisited.isEmpty()) {
            BlockPos seed = unvisited.iterator().next();
            unvisited.remove(seed);
            Set<BlockPos> component = new HashSet<>();
            ArrayDeque<BlockPos> open = new ArrayDeque<>();
            open.add(seed);
            while (!open.isEmpty()) {
                BlockPos current = open.removeFirst();
                component.add(current);
                for (Direction direction : Direction.values()) {
                    if (!vertical && direction.getAxis() == Direction.Axis.Y) continue;
                    BlockPos neighbor = current.offset(direction);
                    if (unvisited.remove(neighbor)) open.addLast(neighbor);
                }
            }
            components.add(component);
        }
        return components;
    }

    private Set<BlockPos> removeNearestComponent(List<Set<BlockPos>> components, BlockPos cursor) {
        Set<BlockPos> result = components.stream()
                .min(Comparator
                        .comparingDouble((Set<BlockPos> component) -> distanceToComponent(component, cursor))
                        .thenComparingInt(component -> -component.size()))
                .orElseThrow();
        components.remove(result);
        return result;
    }

    private double distanceToComponent(Set<BlockPos> component, BlockPos cursor) {
        double best = Double.MAX_VALUE;
        for (BlockPos pos : component) {
            best = Math.min(best, pos.getSquaredDistance(cursor));
        }
        return best;
    }

    private record AxisStep(int x, int y, int z) {
        static AxisStep between(BlockPos from, BlockPos to) {
            int dx = Integer.compare(to.getX(), from.getX());
            int dy = Integer.compare(to.getY(), from.getY());
            int dz = Integer.compare(to.getZ(), from.getZ());
            int changedAxes = (dx == 0 ? 0 : 1) + (dy == 0 ? 0 : 1) + (dz == 0 ? 0 : 1);
            return changedAxes == 1 ? new AxisStep(dx, dy, dz) : null;
        }

        boolean isOpposite(AxisStep other) {
            return x == -other.x && y == -other.y && z == -other.z;
        }
    }

    private enum SurfaceKind {
        VERTICAL_X,
        VERTICAL_Z,
        HORIZONTAL,
        NETWORK
    }

    private record SurfaceKey(SurfaceKind kind, int coordinate) {}

    private record AtomicGroup(Set<BlockPos> positions, int rank) {}

    private record Bounds(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        static Bounds of(Set<BlockPos> positions) {
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
    }

    /**
     * Outdoor air connected through a wide opening. The temporary expanded
     * envelope closes ordinary windows and doors, so their openings cannot
     * turn every wall inside a room into facade; courtyards and broad recesses
     * still retain an exterior air core.
     */
    private static final class OutdoorAirIndex {
        private final Set<BlockPos> occupied;
        private final Set<Long> outdoorAir;
        private final Bounds bounds;
        private final Map<Long, Integer> highestYByXZ;
        private final Map<Long, Extent> xExtentByYZ;
        private final Map<Long, Extent> zExtentByXY;

        private OutdoorAirIndex(
                Set<BlockPos> occupied,
                Set<Long> outdoorAir,
                Bounds bounds,
                Map<Long, Integer> highestYByXZ,
                Map<Long, Extent> xExtentByYZ,
                Map<Long, Extent> zExtentByXY
        ) {
            this.occupied = occupied;
            this.outdoorAir = outdoorAir;
            this.bounds = bounds;
            this.highestYByXZ = highestYByXZ;
            this.xExtentByYZ = xExtentByYZ;
            this.zExtentByXY = zExtentByXY;
        }

        static OutdoorAirIndex create(Set<BlockPos> occupied, Bounds bounds) {
            int searchRadius = EXTERIOR_PORTAL_RADIUS + 1;
            Set<Long> nearbyAir = new HashSet<>(Math.max(16, occupied.size() * 12));
            Set<Long> expandedEnvelope = new HashSet<>(Math.max(16, occupied.size() * 6));
            Map<Long, Integer> highestYByXZ = new HashMap<>();
            Map<Long, Extent> xExtentByYZ = new HashMap<>();
            Map<Long, Extent> zExtentByXY = new HashMap<>();
            for (BlockPos pos : occupied) {
                highestYByXZ.merge(
                        pair(pos.getX(), pos.getZ()), pos.getY(), Math::max);
                xExtentByYZ.merge(
                        pair(pos.getY(), pos.getZ()),
                        new Extent(pos.getX(), pos.getX()), Extent::merge);
                zExtentByXY.merge(
                        pair(pos.getX(), pos.getY()),
                        new Extent(pos.getZ(), pos.getZ()), Extent::merge);
                for (int dx = -searchRadius; dx <= searchRadius; dx++) {
                    for (int dy = -searchRadius; dy <= searchRadius; dy++) {
                        for (int dz = -searchRadius; dz <= searchRadius; dz++) {
                            if (dx == 0 && dy == 0 && dz == 0) continue;
                            int distance = Math.max(
                                    Math.max(Math.abs(dx), Math.abs(dy)), Math.abs(dz));
                            BlockPos air = pos.add(dx, dy, dz);
                            if (occupied.contains(air)) continue;
                            long packed = air.asLong();
                            nearbyAir.add(packed);
                            if (distance <= EXTERIOR_PORTAL_RADIUS) {
                                expandedEnvelope.add(packed);
                            }
                        }
                    }
                }
            }

            Set<Long> exteriorCore = new HashSet<>();
            ArrayDeque<BlockPos> open = new ArrayDeque<>();
            for (long packed : nearbyAir) {
                BlockPos pos = BlockPos.fromLong(packed);
                if (!expandedEnvelope.contains(packed)
                        && touchesBounds(pos, bounds)
                        && exteriorCore.add(packed)) {
                    open.addLast(pos);
                }
            }
            while (!open.isEmpty()) {
                BlockPos current = open.removeFirst();
                for (Direction direction : Direction.values()) {
                    BlockPos neighbor = current.offset(direction);
                    long packed = neighbor.asLong();
                    if (nearbyAir.contains(packed)
                            && !expandedEnvelope.contains(packed)
                            && exteriorCore.add(packed)) {
                        open.addLast(neighbor);
                    }
                }
            }

            Set<Long> outdoor = new HashSet<>(exteriorCore);
            Set<Long> frontier = new HashSet<>(exteriorCore);
            for (int distance = 0; distance < EXTERIOR_SURFACE_REACH; distance++) {
                Set<Long> next = new HashSet<>();
                for (long packed : frontier) {
                    BlockPos current = BlockPos.fromLong(packed);
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dy = -1; dy <= 1; dy++) {
                            for (int dz = -1; dz <= 1; dz++) {
                                if (dx == 0 && dy == 0 && dz == 0) continue;
                                long neighborPacked = current.add(dx, dy, dz).asLong();
                                if (nearbyAir.contains(neighborPacked)
                                        && outdoor.add(neighborPacked)) {
                                    next.add(neighborPacked);
                                }
                            }
                        }
                    }
                }
                frontier = next;
                if (frontier.isEmpty()) break;
            }
            return new OutdoorAirIndex(
                    occupied, outdoor, bounds,
                    highestYByXZ, xExtentByYZ, zExtentByXY);
        }

        boolean isOutdoorAir(BlockPos pos) {
            if (occupied.contains(pos)) return false;
            if (isOutsideBounds(pos, bounds) || outdoorAir.contains(pos.asLong())) return true;

            Integer highestY = highestYByXZ.get(pair(pos.getX(), pos.getZ()));
            if (highestY == null || pos.getY() > highestY) return true;
            Extent xExtent = xExtentByYZ.get(pair(pos.getY(), pos.getZ()));
            if (hasShortClearRay(
                    pos.getX(), xExtent, bounds.minX(), bounds.maxX())) return true;
            Extent zExtent = zExtentByXY.get(pair(pos.getX(), pos.getY()));
            return hasShortClearRay(
                    pos.getZ(), zExtent, bounds.minZ(), bounds.maxZ());
        }

        Set<Direction> horizontalFaces(BlockPos pos) {
            Set<Direction> result = EnumSet.noneOf(Direction.class);
            for (Direction direction : Direction.Type.HORIZONTAL) {
                if (isOutdoorAir(pos.offset(direction))) result.add(direction);
            }
            return result;
        }

        private static boolean isOutsideBounds(BlockPos pos, Bounds bounds) {
            return pos.getX() < bounds.minX() || pos.getX() > bounds.maxX()
                    || pos.getY() < bounds.minY() || pos.getY() > bounds.maxY()
                    || pos.getZ() < bounds.minZ() || pos.getZ() > bounds.maxZ();
        }

        private static boolean touchesBounds(BlockPos pos, Bounds bounds) {
            return pos.getX() <= bounds.minX() || pos.getX() >= bounds.maxX()
                    || pos.getY() <= bounds.minY() || pos.getY() >= bounds.maxY()
                    || pos.getZ() <= bounds.minZ() || pos.getZ() >= bounds.maxZ();
        }

        private static long pair(int first, int second) {
            return ((long) first << 32) ^ (second & 0xffffffffL);
        }

        private static boolean hasShortClearRay(
                int coordinate,
                Extent occupiedExtent,
                int minBound,
                int maxBound
        ) {
            if (occupiedExtent == null) {
                int distance = Math.min(
                        coordinate - minBound + 1,
                        maxBound - coordinate + 1);
                return distance <= MAX_DIRECT_EXTERIOR_DEPTH;
            }
            if (coordinate < occupiedExtent.min()) {
                return coordinate - minBound + 1 <= MAX_DIRECT_EXTERIOR_DEPTH;
            }
            if (coordinate > occupiedExtent.max()) {
                return maxBound - coordinate + 1 <= MAX_DIRECT_EXTERIOR_DEPTH;
            }
            return false;
        }

        private record Extent(int min, int max) {
            static Extent merge(Extent first, Extent second) {
                return new Extent(
                        Math.min(first.min, second.min),
                        Math.max(first.max, second.max));
            }

        }
    }
}

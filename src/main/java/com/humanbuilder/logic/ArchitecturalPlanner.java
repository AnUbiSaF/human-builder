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
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

/**
 * Geometry-first planner for replay-friendly construction. A configurable
 * number of base levels is completed layer by layer before connected phases.
 */
public final class ArchitecturalPlanner {

    public static final int MIN_LAYERED_BASE_HEIGHT = 1;
    public static final int MAX_LAYERED_BASE_HEIGHT = 8;
    public static final int DEFAULT_LAYERED_BASE_HEIGHT = 1;
    private static final int MAX_EXTERIOR_WALL_DEPTH = 1;

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

    public List<BuildEntry> plan(Map<BlockPos, BlockState> blocks, BlockPos start) {
        if (blocks.isEmpty()) return List.of();

        Map<BlockPos, BlockState> immutableBlocks = new HashMap<>(blocks.size());
        for (Map.Entry<BlockPos, BlockState> entry : blocks.entrySet()) {
            immutableBlocks.put(entry.getKey().toImmutable(), entry.getValue());
        }

        Bounds globalBounds = Bounds.of(immutableBlocks.keySet());
        EnumMap<Phase, NavigableMap<Integer, Set<BlockPos>>> positionsByY =
                new EnumMap<>(Phase.class);
        for (Phase phase : Phase.values()) positionsByY.put(phase, new TreeMap<>());

        for (Set<BlockPos> component : connectedComponents(immutableBlocks.keySet())) {
            EnumMap<Phase, Set<BlockPos>> classified = classifyComponent(
                    component, immutableBlocks);
            for (Phase phase : Phase.values()) {
                for (BlockPos pos : classified.get(phase)) {
                    int localY = localY(pos, globalBounds);
                    positionsByY.get(phase)
                            .computeIfAbsent(localY, ignored -> new HashSet<>())
                            .add(pos);
                }
            }
        }

        List<BuildEntry> result = new ArrayList<>(blocks.size());
        Set<BlockPos> planned = new HashSet<>();
        BlockPos cursor = start;
        int workGroup = 1;
        int maxLocalY = globalBounds.maxY() - globalBounds.minY() + 1;
        List<Phase> framePhases = List.of(
                Phase.CONTOUR, Phase.OPAQUE_SHELL, Phase.ROOF);
        List<Phase> basePhases = List.of(
                Phase.CONTOUR, Phase.OPAQUE_SHELL, Phase.ROOF,
                Phase.INTERIOR_STRUCTURE);

        // Y=1..X is a strict layer-by-layer structural base. Glass and decor
        // stay deferred, but floors and other load-bearing interior blocks do not.
        for (int localY = 1; localY <= maxLocalY; localY++) {
            boolean layeredBase = localY <= layeredBaseHeight;
            Map<BlockPos, Phase> phaseByPosition = collectLayer(
                    positionsByY, layeredBase ? basePhases : framePhases, localY);
            List<List<BlockPos>> groups = connectedWorkGroups(
                    phaseByPosition.keySet(), immutableBlocks.keySet(), planned,
                    immutableBlocks, globalBounds, cursor, phaseByPosition);
            for (List<BlockPos> group : groups) {
                for (BlockPos pos : group) {
                    BlockCategory category = layeredBase
                            ? BlockCategory.FOUNDATION
                            : categoryFor(phaseByPosition.get(pos));
                    result.add(new BuildEntry(
                            pos, immutableBlocks.get(pos), category, workGroup));
                    planned.add(pos);
                }
                cursor = group.get(group.size() - 1);
                workGroup++;
            }
        }

        for (Phase phase : List.of(
                Phase.EXTERIOR_FINISH,
                Phase.INTERIOR_STRUCTURE,
                Phase.INTERIOR_DETAIL)) {
            int firstY = phase == Phase.INTERIOR_STRUCTURE
                    ? layeredBaseHeight + 1
                    : 1;
            for (int localY = firstY; localY <= maxLocalY; localY++) {
                Set<BlockPos> positions = positionsByY.get(phase)
                        .getOrDefault(localY, Set.of());
                List<List<BlockPos>> groups = workGroupsForPhase(
                        positions, immutableBlocks.keySet(), planned,
                        immutableBlocks, globalBounds, cursor);
                for (List<BlockPos> group : groups) {
                    BlockCategory category = categoryFor(phase);
                    for (BlockPos pos : group) {
                        result.add(new BuildEntry(
                                pos, immutableBlocks.get(pos), category, workGroup));
                        planned.add(pos);
                    }
                    cursor = group.get(group.size() - 1);
                    workGroup++;
                }
            }
        }
        return result;
    }

    private EnumMap<Phase, Set<BlockPos>> classifyComponent(
            Set<BlockPos> component,
            Map<BlockPos, BlockState> blocks
    ) {
        Bounds bounds = Bounds.of(component);
        OutdoorAirIndex outdoor = OutdoorAirIndex.create(component);
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
                phase = Phase.INTERIOR_DETAIL;
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
        for (BlockPos pos : component) {
            if (!outdoorFacesByPosition.get(pos).isEmpty()
                    && isContourEligible(blocks.get(pos))) {
                shell.add(pos);
            }
        }

        for (BlockPos surface : List.copyOf(shell)) {
            for (Direction exteriorFace : outdoorFacesByPosition.get(surface)) {
                BlockPos cursor = surface;
                for (int depth = 0; depth < MAX_EXTERIOR_WALL_DEPTH; depth++) {
                    cursor = cursor.offset(exteriorFace.getOpposite());
                    if (!component.contains(cursor)
                            || !isContourEligible(blocks.get(cursor))) {
                        break;
                    }
                    shell.add(cursor);
                }
            }
        }
        return shell;
    }

    private Map<BlockPos, Phase> collectLayer(
            EnumMap<Phase, NavigableMap<Integer, Set<BlockPos>>> positionsByY,
            List<Phase> phases,
            int localY
    ) {
        Map<BlockPos, Phase> result = new HashMap<>();
        for (Phase phase : phases) {
            for (BlockPos pos : positionsByY.get(phase).getOrDefault(localY, Set.of())) {
                result.put(pos, phase);
            }
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
     * Air visible from the sky or along an unobstructed horizontal exterior ray.
     * Horizontal rays recognize deep walls below wide eaves without flooding
     * through glass into closed rooms.
     */
    private static final class OutdoorAirIndex {
        private final Set<BlockPos> occupied;
        private final Map<Long, Integer> highestYByXZ;
        private final Map<Long, Extent> xExtentByYZ;
        private final Map<Long, Extent> zExtentByXY;

        private OutdoorAirIndex(
                Set<BlockPos> occupied,
                Map<Long, Integer> highestYByXZ,
                Map<Long, Extent> xExtentByYZ,
                Map<Long, Extent> zExtentByXY
        ) {
            this.occupied = occupied;
            this.highestYByXZ = highestYByXZ;
            this.xExtentByYZ = xExtentByYZ;
            this.zExtentByXY = zExtentByXY;
        }

        static OutdoorAirIndex create(Set<BlockPos> occupied) {
            Map<Long, Integer> highestYByXZ = new HashMap<>();
            Map<Long, Extent> xExtentByYZ = new HashMap<>();
            Map<Long, Extent> zExtentByXY = new HashMap<>();
            for (BlockPos pos : occupied) {
                highestYByXZ.merge(
                        pair(pos.getX(), pos.getZ()), pos.getY(), Math::max);
                xExtentByYZ.merge(
                        pair(pos.getY(), pos.getZ()),
                        new Extent(pos.getX(), pos.getX()),
                        Extent::merge);
                zExtentByXY.merge(
                        pair(pos.getX(), pos.getY()),
                        new Extent(pos.getZ(), pos.getZ()),
                        Extent::merge);
            }
            return new OutdoorAirIndex(
                    occupied, highestYByXZ, xExtentByYZ, zExtentByXY);
        }

        boolean isOutdoorAir(BlockPos pos) {
            if (occupied.contains(pos)) return false;
            Integer highestY = highestYByXZ.get(pair(pos.getX(), pos.getZ()));
            if (highestY == null || pos.getY() > highestY) return true;

            Extent xExtent = xExtentByYZ.get(pair(pos.getY(), pos.getZ()));
            if (xExtent == null || xExtent.isOutside(pos.getX())) return true;

            Extent zExtent = zExtentByXY.get(pair(pos.getX(), pos.getY()));
            return zExtent == null || zExtent.isOutside(pos.getZ());
        }

        Set<Direction> horizontalFaces(BlockPos pos) {
            Set<Direction> result = EnumSet.noneOf(Direction.class);
            for (Direction direction : Direction.Type.HORIZONTAL) {
                if (isOutdoorAir(pos.offset(direction))) result.add(direction);
            }
            return result;
        }

        private static long pair(int first, int second) {
            return ((long) first << 32) ^ (second & 0xffffffffL);
        }

        private record Extent(int min, int max) {
            static Extent merge(Extent first, Extent second) {
                return new Extent(
                        Math.min(first.min, second.min),
                        Math.max(first.max, second.max));
            }

            boolean isOutside(int value) {
                return value < min || value > max;
            }
        }
    }
}

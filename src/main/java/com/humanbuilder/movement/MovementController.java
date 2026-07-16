package com.humanbuilder.movement;

import com.humanbuilder.HumanBuilderMod;
import com.humanbuilder.camera.CameraSmoother;
import com.humanbuilder.placer.BlockPlacer;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Moves the player using normal movement keys and collision-aware paths.
 * Creative flight is preferred while building so the player stays above the
 * schematic; the ground pathfinder remains available outside Creative mode.
 */
public class MovementController {

    private static final double REACH_DISTANCE = 4.45;
    private static final double WAYPOINT_DISTANCE = 0.32;
    private static final double FINAL_DISTANCE = 0.20;
    private static final double FLIGHT_CRUISE_SPEED = 0.48;
    private static final double FLIGHT_ACCELERATION = 0.16;
    private static final int MAX_GROUND_NODES = 5_000;
    private static final int MAX_FLIGHT_NODES = 7_000;
    private static final int REPLAN_AFTER_TICKS = 12;
    private static final int ENDPOINT_REPLAN_TICKS = 8;
    private static final double PROGRESS_EPSILON = 0.008;
    private static final int[][] HORIZONTAL_DIRECTIONS = {
            { 1, 0}, {-1, 0}, {0,  1}, {0, -1},
            { 1, 1}, { 1,-1}, {-1, 1}, {-1,-1}
    };

    private final MinecraftClient client;
    private final CameraSmoother camera;
    private final BlockPlacer placer;

    private BlockPos targetBlockPos;
    private BlockState targetBlockState;
    private Vec3d standingTarget;
    private final List<Vec3d> waypoints = new ArrayList<>();
    private int waypointIndex;
    private boolean active;
    private boolean flyingRoute;
    private int noProgressTicks;
    private int blockedEndpointTicks;
    private int replanAttempts;
    private final Set<BlockPos> attemptedStandingPositions = new HashSet<>();
    private double bestWaypointDistance = Double.MAX_VALUE;
    private Boolean flyingBeforeControl;
    private boolean flightOwned;
    private boolean routeFailed;
    private String failureReason;
    private BlockPos blockingObstacle;
    private boolean planningPending;
    private int planningHoldTicks;

    public MovementController(MinecraftClient client, CameraSmoother camera, BlockPlacer placer) {
        this.client = client;
        this.camera = camera;
        this.placer = placer;
    }

    public boolean walkTo(BlockPos target, BlockState state) {
        return walkTo(target, state, false);
    }

    public boolean walkTo(BlockPos target, BlockState state, boolean forceDifferent) {
        return navigateTo(target, state, forceDifferent);
    }

    public boolean walkToBreak(BlockPos target) {
        boolean retryingSameTarget = targetBlockPos != null
                && targetBlockPos.equals(target)
                && targetBlockState == null
                && !attemptedStandingPositions.isEmpty();
        return navigateTo(target, null, retryingSameTarget);
    }

    private boolean navigateTo(BlockPos target, BlockState state, boolean forceDifferent) {
        if (client.player == null || client.world == null) {
            failRoute("игрок или мир недоступен");
            return false;
        }

        boolean continuingSameTarget = targetBlockPos != null
                && targetBlockPos.equals(target)
                && java.util.Objects.equals(targetBlockState, state);
        if (!continuingSameTarget) {
            attemptedStandingPositions.clear();
        } else if (standingTarget != null) {
            attemptedStandingPositions.add(BlockPos.ofFloored(standingTarget));
        }

        routeFailed = false;
        failureReason = null;
        blockingObstacle = null;
        holdStillForPlanning();

        if (flyingBeforeControl == null) {
            flyingBeforeControl = client.player.getAbilities().flying;
        }

        targetBlockPos = target.toImmutable();
        targetBlockState = state;
        placer.clearPlacementObstruction();
        placer.clearProbeObstruction();
        standingTarget = findStandingPosition(targetBlockPos, forceDifferent);
        if (standingTarget == null) {
            active = false;
            releaseAllKeys();
            failRoute("не найдена свободная позиция с видимой гранью блока");
            return false;
        }
        noProgressTicks = 0;
        blockedEndpointTicks = 0;
        replanAttempts = 0;
        scheduleRoutePlanning(1);
        return true;
    }

    public void tick() {
        if (!active || client.player == null || client.world == null || standingTarget == null) return;

        if (planningPending) {
            holdStillForPlanning();
            if (planningHoldTicks-- > 0) return;

            planningPending = false;
            long planningStarted = System.nanoTime();
            boolean routePlanned = planRoute();
            long planningMillis = (System.nanoTime() - planningStarted) / 1_000_000L;
            if (planningMillis >= 40L) {
                HumanBuilderMod.LOGGER.warn("[HumanBuilder] Route planning to {} took {} ms",
                        targetBlockPos.toShortString(), planningMillis);
            }
            if (!routePlanned) {
                active = false;
                failRoute("безопасный маршрут к позиции не найден");
            }
            return;
        }

        ClientPlayerEntity player = client.player;
        advanceReachedWaypoints(player.getPos());

        if (isBlockedAtFinalApproach(player.getPos())) {
            blockedEndpointTicks++;
            if (blockedEndpointTicks >= ENDPOINT_REPLAN_TICKS) {
                HumanBuilderMod.LOGGER.info(
                        "[HumanBuilder] Final approach to {} lost its interaction face; replanning",
                        targetBlockPos.toShortString());
                handleNoProgress();
                return;
            }
        } else {
            blockedEndpointTicks = 0;
        }

        if (isAtDestination()) {
            stop();
            return;
        }

        if (waypointIndex >= waypoints.size()) {
            handleNoProgress();
            return;
        }

        Vec3d waypoint = waypoints.get(waypointIndex);
        Vec3d playerPos = player.getPos();
        double dx = waypoint.x - playerPos.x;
        double dz = waypoint.z - playerPos.z;
        double horizontalDistance = Math.hypot(dx, dz);
        double distance = flyingRoute
                ? playerPos.distanceTo(waypoint)
                : Math.hypot(horizontalDistance, Math.max(0.0, waypoint.y - playerPos.y));

        updateProgress(distance);

        releaseMovementKeys();
        player.setSprinting(false);

        float desiredYaw = horizontalDistance > 0.08
                ? (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0)
                : player.getYaw();
        float yawError = MathHelper.wrapDegrees(desiredYaw - player.getYaw());

        if (flyingRoute) {
            // Camera is a placement tool, not a steering wheel. Keeping it out
            // of the flight feedback loop prevents delayed 180-degree turns
            // from turning a small overshoot into an endless orbit.
            camera.stop();
            applyFlightVelocity(player, waypoint, horizontalDistance);
        } else {
            if (horizontalDistance > 0.08) {
                double lookDistance = Math.min(2.5, horizontalDistance);
                camera.lookAt(
                        playerPos.x + dx / horizontalDistance * lookDistance,
                        player.getEyeY(),
                        playerPos.z + dz / horizontalDistance * lookDistance
                );
            }
            if (Math.abs(yawError) < 58.0f && horizontalDistance > 0.08) {
                applyGroundHorizontalInput(player, horizontalDistance, yawError);
            }
            applyGroundInput(player, waypoint);
        }

        if (noProgressTicks >= REPLAN_AFTER_TICKS) {
            handleNoProgress();
        }
    }

    private void applyGroundHorizontalInput(ClientPlayerEntity player, double horizontalDistance, float yawError) {
        double routeRemaining = remainingRouteDistance(player.getPos());

        client.options.forwardKey.setPressed(true);

        if (waypointIndex == waypoints.size() - 1 && horizontalDistance < 1.15) {
            client.options.sneakKey.setPressed(true);
        } else if (routeRemaining > 5.5 && Math.abs(yawError) < 10.0f && !isCornerAhead()) {
            player.setSprinting(true);
        }
    }

    private void applyFlightVelocity(ClientPlayerEntity player, Vec3d waypoint,
                                     double horizontalDistance) {
        Vec3d currentVelocity = player.getVelocity();
        boolean finalWaypoint = waypointIndex == waypoints.size() - 1;
        boolean interactionReady = finalWaypoint && targetBlockPos != null
                && canInteractFromCurrentPosition();

        double speedLimit = finalWaypoint ? 0.38 : FLIGHT_CRUISE_SPEED;
        double horizontalSpeed = Math.min(speedLimit, horizontalDistance * 0.48);
        double horizontalStopDistance = interactionReady ? 0.72 : (finalWaypoint ? 0.035 : 0.20);
        if (horizontalDistance < horizontalStopDistance) {
            horizontalSpeed = 0.0;
        }

        double desiredX = horizontalDistance > 0.001
                ? (waypoint.x - player.getX()) / horizontalDistance * horizontalSpeed
                : 0.0;
        double desiredZ = horizontalDistance > 0.001
                ? (waypoint.z - player.getZ()) / horizontalDistance * horizontalSpeed
                : 0.0;

        double dy = waypoint.y - player.getY();
        double desiredY = MathHelper.clamp(dy * 0.42, -0.40, 0.40);
        double verticalStopDistance = interactionReady ? 0.70 : (finalWaypoint ? 0.05 : 0.18);
        if (Math.abs(dy) < verticalStopDistance) {
            desiredY = 0.0;
        }

        double acceleration = interactionReady ? 0.24 : FLIGHT_ACCELERATION;
        player.setVelocity(
                approach(currentVelocity.x, desiredX, acceleration),
                approach(currentVelocity.y, desiredY, acceleration),
                approach(currentVelocity.z, desiredZ, acceleration)
        );
    }

    private void applyGroundInput(ClientPlayerEntity player, Vec3d waypoint) {
        boolean steppingUp = waypoint.y > player.getY() + 0.35;
        if (player.isOnGround() && (steppingUp || player.horizontalCollision)) {
            client.options.jumpKey.setPressed(true);
        }
    }

    private void advanceReachedWaypoints(Vec3d playerPos) {
        while (waypointIndex < waypoints.size()) {
            Vec3d waypoint = waypoints.get(waypointIndex);
            double horizontal = Math.hypot(waypoint.x - playerPos.x, waypoint.z - playerPos.z);
            double vertical = Math.abs(waypoint.y - playerPos.y);
            double threshold = waypointIndex == waypoints.size() - 1
                    ? (flyingRoute ? 0.40 : FINAL_DISTANCE)
                    : (flyingRoute ? 0.58 : WAYPOINT_DISTANCE);
            double verticalThreshold = flyingRoute ? 0.55 : 1.15;
            double distance = playerPos.distanceTo(waypoint);
            boolean passedFlightWaypoint = flyingRoute
                    && waypointIndex < waypoints.size() - 1
                    && bestWaypointDistance < 0.85
                    && distance > bestWaypointDistance + 0.18;
            if (!passedFlightWaypoint && (horizontal > threshold || vertical > verticalThreshold)) break;
            if (waypointIndex == waypoints.size() - 1
                    && targetBlockPos != null
                    && !canInteractFromCurrentPosition()) break;

            waypointIndex++;
            bestWaypointDistance = Double.MAX_VALUE;
            noProgressTicks = 0;
        }
    }

    private void updateProgress(double distance) {
        if (distance + PROGRESS_EPSILON < bestWaypointDistance) {
            bestWaypointDistance = distance;
            noProgressTicks = 0;
        } else {
            noProgressTicks++;
        }
    }

    private void handleNoProgress() {
        holdStillForPlanning();
        noProgressTicks = 0;
        replanAttempts++;

        if (targetBlockPos != null) {
            Vec3d alternative = findStandingPosition(targetBlockPos, true);
            if (alternative != null) {
                standingTarget = alternative;
                HumanBuilderMod.LOGGER.info(
                        "[HumanBuilder] Switching approach point for {} after no progress",
                        targetBlockPos.toShortString());
            }
        }

        if (replanAttempts <= 3) {
            scheduleRoutePlanning(1);
            return;
        }

        if (!flyingRoute && canUseCreativeFlight() && planFlightRoute()) {
            active = true;
            return;
        }

        active = false;
        failRoute("нет прогресса после повторного построения маршрута");
    }

    public boolean isWithinReach(BlockPos pos) {
        if (client.player == null) return false;
        return client.player.getEyePos().distanceTo(Vec3d.ofCenter(pos)) <= REACH_DISTANCE;
    }

    public boolean hasArrived() {
        return active && isAtDestination();
    }

    public boolean isAtDestination() {
        if (client.player == null || standingTarget == null) return false;
        Vec3d pos = client.player.getPos();
        double horizontal = Math.hypot(standingTarget.x - pos.x, standingTarget.z - pos.z);
        double vertical = Math.abs(standingTarget.y - pos.y);
        return horizontal < (flyingRoute ? 0.40 : FINAL_DISTANCE)
                && vertical < (flyingRoute ? 0.65 : 1.15)
                && targetBlockPos != null
                && canInteractFromCurrentPosition();
    }

    /** Stops current input but preserves flight between nearby build targets. */
    public void stop() {
        active = false;
        planningPending = false;
        planningHoldTicks = 0;
        waypoints.clear();
        waypointIndex = 0;
        noProgressTicks = 0;
        blockedEndpointTicks = 0;
        bestWaypointDistance = Double.MAX_VALUE;
        releaseAllKeys();
        settlePlayerMotion();
        if (client.player != null) client.player.setSprinting(false);
    }

    /** Stops all movement and restores the flight state from before automation. */
    public void reset() {
        stop();
        if (client.player != null && flightOwned && Boolean.FALSE.equals(flyingBeforeControl)
                && client.player.getAbilities().flying) {
            client.player.getAbilities().flying = false;
            client.player.sendAbilitiesUpdate();
        }
        targetBlockPos = null;
        targetBlockState = null;
        standingTarget = null;
        attemptedStandingPositions.clear();
        flyingRoute = false;
        flightOwned = false;
        flyingBeforeControl = null;
        routeFailed = false;
        failureReason = null;
        blockingObstacle = null;
    }

    public boolean isActive() {
        return active;
    }

    public BlockPos getTargetPos() {
        return targetBlockPos;
    }

    public boolean hasRouteFailure() {
        return routeFailed;
    }

    public String consumeFailureReason() {
        String reason = failureReason == null ? "неизвестная ошибка навигации" : failureReason;
        routeFailed = false;
        failureReason = null;
        return reason;
    }

    public BlockPos consumeBlockingObstacle() {
        BlockPos obstacle = blockingObstacle;
        blockingObstacle = null;
        return obstacle;
    }

    private boolean planRoute() {
        if (client.player == null || standingTarget == null) return false;

        BlockPos goal = BlockPos.ofFloored(standingTarget);
        if (canUseCreativeFlight()) {
            return planFlightRoute();
        }

        BlockPos start = findGroundStart(client.player.getBlockPos());
        List<BlockPos> path = start == null ? List.of() : findGroundPath(start, goal);
        if (!path.isEmpty() || (start != null && start.equals(goal))) {
            flyingRoute = false;
            setWaypoints(path, standingTarget);
            return true;
        }

        return false;
    }

    private boolean planFlightRoute() {
        if (client.player == null || standingTarget == null || !canUseCreativeFlight()) return false;

        BlockPos start = client.player.getBlockPos();
        BlockPos goal = BlockPos.ofFloored(standingTarget);
        List<BlockPos> path = findDirectFlightPath(start, goal);
        if (path.isEmpty() && !start.equals(goal)) {
            path = findFlightPath(start, goal);
        }
        if (path.isEmpty() && !start.equals(goal)) return false;

        blockingObstacle = null;
        enableFlight();
        flyingRoute = true;
        setWaypoints(path, standingTarget);
        return true;
    }

    private void setWaypoints(List<BlockPos> path, Vec3d exactTarget) {
        waypoints.clear();
        for (BlockPos pos : simplifyPath(path)) {
            waypoints.add(Vec3d.ofBottomCenter(pos));
        }
        if (waypoints.isEmpty() || waypoints.get(waypoints.size() - 1).distanceTo(exactTarget) > 0.05) {
            waypoints.add(exactTarget);
        }
        waypointIndex = 0;
        bestWaypointDistance = Double.MAX_VALUE;
        noProgressTicks = 0;
        blockedEndpointTicks = 0;
    }

    private Vec3d findStandingPosition(BlockPos targetPos, boolean forceDifferent) {
        Vec3d playerPos = client.player.getPos();
        BlockPos candidate = null;
        if (canUseCreativeFlight()) {
            candidate = findStandingCandidate(targetPos, playerPos, forceDifferent, false);
        }
        if (candidate == null) {
            candidate = findStandingCandidate(targetPos, playerPos, forceDifferent, true);
        }
        if (candidate == null) return null;
        attemptedStandingPositions.add(candidate.toImmutable());
        return Vec3d.ofBottomCenter(candidate);
    }

    private BlockPos findStandingCandidate(BlockPos targetPos, Vec3d playerPos, boolean forceDifferent,
                                           boolean requireGround) {
        List<StandingCandidate> candidates = new ArrayList<>();

        for (int radius = 2; radius <= 4; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) continue;

                    for (int dy = -2; dy <= 3; dy++) {
                        BlockPos candidate = targetPos.add(dx, dy, dz);
                        if (requireGround ? !isStandable(candidate) : !isBodyClear(candidate)) continue;

                        Vec3d feet = Vec3d.ofBottomCenter(candidate);
                        if (forceDifferent && (attemptedStandingPositions.contains(candidate)
                                || feet.distanceTo(playerPos) < 1.0)) continue;

                        Vec3d eye = feet.add(0, 1.62, 0);
                        if (eye.distanceTo(Vec3d.ofCenter(targetPos)) > REACH_DISTANCE) continue;
                        double score = feet.squaredDistanceTo(playerPos) + radius * 0.35 + Math.abs(dy) * 1.5;
                        if (!requireGround && dy < 2) score += (2 - dy) * 25.0;
                        candidates.add(new StandingCandidate(candidate, eye, score));
                    }
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(StandingCandidate::score));
        for (StandingCandidate candidate : candidates) {
            boolean canInteract = targetBlockState == null
                    ? placer.canBreakFrom(candidate.eyePos(), targetPos)
                    : placer.canPlaceFrom(candidate.eyePos(), targetPos, targetBlockState);
            if (canInteract) {
                blockingObstacle = null;
                placer.clearProbeObstruction();
                return candidate.pos();
            }
            BlockPos probeObstacle = placer.consumeProbeObstruction();
            if (blockingObstacle == null && probeObstacle != null) {
                blockingObstacle = probeObstacle.toImmutable();
            }
        }
        return null;
    }

    private List<BlockPos> findDirectFlightPath(BlockPos start, BlockPos goal) {
        if (start.equals(goal)) return List.of();

        int targetY = targetBlockPos == null ? goal.getY() : targetBlockPos.getY();
        int cruiseY = Math.max(Math.max(start.getY(), goal.getY()), targetY + 3);
        List<BlockPos> path = new ArrayList<>();

        if (!appendClearLine(path, start, new BlockPos(start.getX(), cruiseY, start.getZ()))) return List.of();
        BlockPos cruiseGoal = new BlockPos(goal.getX(), cruiseY, goal.getZ());
        if (!appendClearLine(path, path.isEmpty() ? start : path.get(path.size() - 1), cruiseGoal)) return List.of();
        if (!appendClearLine(path, path.isEmpty() ? start : path.get(path.size() - 1), goal)) return List.of();
        return path;
    }

    private boolean appendClearLine(List<BlockPos> path, BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dy = to.getY() - from.getY();
        int dz = to.getZ() - from.getZ();
        int steps = Math.max(Math.max(Math.abs(dx), Math.abs(dy)), Math.abs(dz));
        if (steps == 0) return true;

        BlockPos previous = from;
        for (int step = 1; step <= steps; step++) {
            BlockPos current = new BlockPos(
                    from.getX() + Math.round((float) dx * step / steps),
                    from.getY() + Math.round((float) dy * step / steps),
                    from.getZ() + Math.round((float) dz * step / steps)
            );
            if (!current.equals(previous)) {
                if (current.getX() != previous.getX() && current.getZ() != previous.getZ()
                        && (!isBodyClear(new BlockPos(current.getX(), current.getY(), previous.getZ()))
                        || !isBodyClear(new BlockPos(previous.getX(), current.getY(), current.getZ())))) {
                    rememberBodyObstacle(new BlockPos(current.getX(), current.getY(), previous.getZ()));
                    rememberBodyObstacle(new BlockPos(previous.getX(), current.getY(), current.getZ()));
                    return false;
                }
                if (!isBodyClear(current)) {
                    rememberBodyObstacle(current);
                    return false;
                }
                path.add(current);
                previous = current;
            }
        }
        return true;
    }

    private BlockPos findGroundStart(BlockPos around) {
        int[] yOffsets = {0, -1, 1, -2, 2};
        for (int yOffset : yOffsets) {
            BlockPos candidate = around.add(0, yOffset, 0);
            if (isStandable(candidate)) return candidate;
        }
        return null;
    }

    private List<BlockPos> findGroundPath(BlockPos start, BlockPos goal) {
        PriorityQueue<PathNode> open = new PriorityQueue<>(Comparator.comparingDouble(PathNode::score));
        Map<BlockPos, Double> costs = new HashMap<>();
        Map<BlockPos, BlockPos> parents = new HashMap<>();
        Set<BlockPos> closed = new HashSet<>();

        costs.put(start, 0.0);
        open.add(new PathNode(start, heuristic(start, goal)));
        int visited = 0;

        while (!open.isEmpty() && visited++ < MAX_GROUND_NODES) {
            BlockPos current = open.poll().pos();
            if (!closed.add(current)) continue;
            if (current.equals(goal)) return reconstructPath(parents, start, goal);

            for (int[] direction : HORIZONTAL_DIRECTIONS) {
                BlockPos next = findGroundNeighbor(current, direction[0], direction[1]);
                if (next == null || closed.contains(next)) continue;
                if (Math.abs(next.getX() - start.getX()) > 48 || Math.abs(next.getZ() - start.getZ()) > 48) continue;

                double step = direction[0] != 0 && direction[1] != 0 ? 1.414 : 1.0;
                step += Math.abs(next.getY() - current.getY()) * 0.45;
                double newCost = costs.get(current) + step;
                if (newCost >= costs.getOrDefault(next, Double.MAX_VALUE)) continue;

                costs.put(next, newCost);
                parents.put(next, current);
                open.add(new PathNode(next, newCost + heuristic(next, goal)));
            }
        }
        return List.of();
    }

    private BlockPos findGroundNeighbor(BlockPos current, int dx, int dz) {
        if (dx != 0 && dz != 0
                && !isBodyClear(current.add(dx, 0, 0))
                && !isBodyClear(current.add(0, 0, dz))) {
            return null;
        }

        int[] yOffsets = {0, 1, -1, -2};
        for (int yOffset : yOffsets) {
            BlockPos candidate = current.add(dx, yOffset, dz);
            if (isStandable(candidate)) return candidate;
        }
        return null;
    }

    private List<BlockPos> findFlightPath(BlockPos start, BlockPos goal) {
        PriorityQueue<PathNode> open = new PriorityQueue<>(Comparator.comparingDouble(PathNode::score));
        Map<BlockPos, Double> costs = new HashMap<>();
        Map<BlockPos, BlockPos> parents = new HashMap<>();
        Set<BlockPos> closed = new HashSet<>();
        costs.put(start, 0.0);
        open.add(new PathNode(start, heuristic(start, goal)));
        int visited = 0;

        while (!open.isEmpty() && visited++ < MAX_FLIGHT_NODES) {
            BlockPos current = open.poll().pos();
            if (!closed.add(current)) continue;
            if (current.equals(goal)) return reconstructPath(parents, start, goal);

            for (int[] direction : HORIZONTAL_DIRECTIONS) {
                int dx = direction[0];
                int dz = direction[1];
                BlockPos next = current.add(dx, 0, dz);
                if (dx != 0 && dz != 0
                        && (!isBodyClear(current.add(dx, 0, 0))
                        || !isBodyClear(current.add(0, 0, dz)))) continue;
                if (closed.contains(next) || !isBodyClear(next)) continue;
                if (Math.abs(next.getX() - start.getX()) > 40
                        || Math.abs(next.getY() - start.getY()) > 32
                        || Math.abs(next.getZ() - start.getZ()) > 40) continue;

                double newCost = costs.get(current) + (dx != 0 && dz != 0 ? 1.414 : 1.0);
                if (newCost >= costs.getOrDefault(next, Double.MAX_VALUE)) continue;
                costs.put(next, newCost);
                parents.put(next, current);
                open.add(new PathNode(next, newCost + heuristic(next, goal)));
            }

            for (int dy : new int[]{1, -1}) {
                BlockPos next = current.add(0, dy, 0);
                if (closed.contains(next) || !isBodyClear(next)) continue;
                if (Math.abs(next.getY() - start.getY()) > 32) continue;

                double newCost = costs.get(current) + 1.0;
                if (newCost >= costs.getOrDefault(next, Double.MAX_VALUE)) continue;
                costs.put(next, newCost);
                parents.put(next, current);
                open.add(new PathNode(next, newCost + heuristic(next, goal)));
            }
        }
        return List.of();
    }

    private List<BlockPos> reconstructPath(Map<BlockPos, BlockPos> parents, BlockPos start, BlockPos goal) {
        Deque<BlockPos> reversed = new ArrayDeque<>();
        BlockPos current = goal;
        while (!current.equals(start)) {
            reversed.addFirst(current);
            current = parents.get(current);
            if (current == null) return List.of();
        }
        return new ArrayList<>(reversed);
    }

    private List<BlockPos> simplifyPath(List<BlockPos> path) {
        if (path.size() < 3) return path;
        List<BlockPos> result = new ArrayList<>();
        BlockPos previous = path.get(0);
        int lastDx = Integer.signum(previous.getX() - client.player.getBlockX());
        int lastDy = Integer.signum(previous.getY() - client.player.getBlockY());
        int lastDz = Integer.signum(previous.getZ() - client.player.getBlockZ());

        for (int i = 1; i < path.size(); i++) {
            BlockPos current = path.get(i);
            int dx = Integer.signum(current.getX() - previous.getX());
            int dy = Integer.signum(current.getY() - previous.getY());
            int dz = Integer.signum(current.getZ() - previous.getZ());
            if (dx != lastDx || dy != lastDy || dz != lastDz) result.add(previous);
            lastDx = dx;
            lastDy = dy;
            lastDz = dz;
            previous = current;
        }
        result.add(path.get(path.size() - 1));
        return result;
    }

    private boolean isStandable(BlockPos feet) {
        if (!isBodyClear(feet)) return false;
        BlockPos supportPos = feet.down();
        BlockState support = client.world.getBlockState(supportPos);
        return !support.getCollisionShape(client.world, supportPos).isEmpty()
                && client.world.getFluidState(feet).isEmpty();
    }

    private boolean isBodyClear(BlockPos feet) {
        Vec3d center = Vec3d.ofBottomCenter(feet);
        Box playerBox = new Box(
                center.x - 0.30, center.y + 0.001, center.z - 0.30,
                center.x + 0.30, center.y + 1.80, center.z + 0.30
        );
        return client.world.isSpaceEmpty(client.player, playerBox)
                && client.world.getFluidState(feet).isEmpty()
                && client.world.getFluidState(feet.up()).isEmpty();
    }

    private void rememberBodyObstacle(BlockPos feet) {
        if (blockingObstacle != null || client.world == null) return;
        for (BlockPos pos : List.of(feet, feet.up())) {
            BlockState state = client.world.getBlockState(pos);
            if (!state.isReplaceable() && !state.getCollisionShape(client.world, pos).isEmpty()) {
                blockingObstacle = pos.toImmutable();
                return;
            }
        }
    }

    private double remainingRouteDistance(Vec3d from) {
        if (waypointIndex >= waypoints.size()) return 0.0;
        double distance = from.distanceTo(waypoints.get(waypointIndex));
        for (int i = waypointIndex + 1; i < waypoints.size(); i++) {
            distance += waypoints.get(i - 1).distanceTo(waypoints.get(i));
        }
        return distance;
    }

    private boolean isCornerAhead() {
        if (waypointIndex + 1 >= waypoints.size()) return false;
        Vec3d current = waypoints.get(waypointIndex);
        Vec3d next = waypoints.get(waypointIndex + 1);
        Vec3d player = client.player.getPos();
        double firstYaw = Math.atan2(current.z - player.z, current.x - player.x);
        double secondYaw = Math.atan2(next.z - current.z, next.x - current.x);
        return Math.abs(MathHelper.wrapDegrees((float) Math.toDegrees(secondYaw - firstYaw))) > 25.0f;
    }

    private double heuristic(BlockPos from, BlockPos to) {
        return Math.hypot(from.getX() - to.getX(), from.getZ() - to.getZ())
                + Math.abs(from.getY() - to.getY()) * 0.75;
    }

    private boolean canUseCreativeFlight() {
        return client.player != null && client.player.getAbilities().creativeMode;
    }

    private void enableFlight() {
        if (!client.player.getAbilities().flying) {
            client.player.getAbilities().flying = true;
            client.player.sendAbilitiesUpdate();
            flightOwned = true;
        }
    }

    private void failRoute(String reason) {
        routeFailed = true;
        failureReason = reason;
        HumanBuilderMod.LOGGER.warn("[HumanBuilder] Navigation failed for {}: {}",
                targetBlockPos == null ? "unknown" : targetBlockPos.toShortString(), reason);
    }

    public void failActiveRoute(String reason) {
        stop();
        failRoute(reason);
    }

    private boolean canInteractFromCurrentPosition() {
        if (targetBlockPos == null) return false;
        return targetBlockState == null
                ? placer.canBreakFromCurrentPosition(targetBlockPos)
                : placer.canPlaceFromCurrentPosition(targetBlockPos, targetBlockState);
    }

    private boolean isBlockedAtFinalApproach(Vec3d playerPos) {
        if (targetBlockPos == null || standingTarget == null || waypoints.isEmpty()
                || waypointIndex != waypoints.size() - 1) return false;
        double horizontal = Math.hypot(standingTarget.x - playerPos.x, standingTarget.z - playerPos.z);
        double vertical = Math.abs(standingTarget.y - playerPos.y);
        return horizontal < 0.13 && vertical < 0.16 && !canInteractFromCurrentPosition();
    }

    private void scheduleRoutePlanning(int holdTicks) {
        active = true;
        planningPending = true;
        planningHoldTicks = Math.max(0, holdTicks);
        waypoints.clear();
        waypointIndex = 0;
        holdStillForPlanning();
    }

    private void holdStillForPlanning() {
        releaseAllKeys();
        camera.stop();
        settlePlayerMotion();
        if (client.player != null) client.player.setSprinting(false);
    }

    private void settlePlayerMotion() {
        if (client.player == null) return;
        Vec3d velocity = client.player.getVelocity();
        double vertical = client.player.getAbilities().flying || flyingRoute ? 0.0 : velocity.y;
        client.player.setVelocity(0.0, vertical, 0.0);
    }

    private double approach(double current, double target, double maxDelta) {
        return current + MathHelper.clamp(target - current, -maxDelta, maxDelta);
    }

    private void releaseMovementKeys() {
        if (client.options == null) return;
        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        client.options.sneakKey.setPressed(false);
    }

    private void releaseAllKeys() {
        releaseMovementKeys();
        if (client.options != null) client.options.sprintKey.setPressed(false);
    }

    private record PathNode(BlockPos pos, double score) {}
    private record StandingCandidate(BlockPos pos, Vec3d eyePos, double score) {}
}

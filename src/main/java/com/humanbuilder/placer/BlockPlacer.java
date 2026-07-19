package com.humanbuilder.placer;

import com.humanbuilder.HumanBuilderMod;
import net.minecraft.block.AmethystClusterBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.EndRodBlock;
import net.minecraft.block.FallingBlock;
import net.minecraft.block.LadderBlock;
import net.minecraft.block.RodBlock;
import net.minecraft.block.SkullBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.WallBannerBlock;
import net.minecraft.block.WallMountedBlock;
import net.minecraft.block.WallSignBlock;
import net.minecraft.block.WallSkullBlock;
import net.minecraft.block.WallTorchBlock;
import net.minecraft.block.enums.BlockFace;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.CreativeInventoryActionC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationPropertyHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.WorldView;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Модуль установки блоков.
 *
 * Отвечает за:
 *   1. Поиск нужного блока в хотбаре и переключение на него.
 *   2. Определение, на какую грань соседнего блока нужно «кликнуть».
 *   3. Вызов interactionManager.interactBlock() для легитимной установки.
 *
 * Принципиальное отличие от читов: мы не вызываем world.setBlockState(),
 * а симулируем настоящий клик мышкой, который проходит через сервер.
 */
public class BlockPlacer {

    private static final Set<String> MANAGED_STATE_PROPERTIES = Set.of(
            "facing", "axis", "half", "type", "rotation", "face",
            "attachment", "hinge", "part"
    );

    private final MinecraftClient client;
    private BlockPos lastPlacementObstruction;
    private BlockPos lastProbeObstruction;
    private BlockPos lastProtectedDependent;
    private boolean lineOfSightRequired = false;

    private Direction[] getFacePriority(BlockState state) {
        if (state != null && state.getBlock() instanceof SlabBlock) {
            return slabSupportDirections(state.get(Properties.SLAB_TYPE));
        }

        if (isWallMounted(state)) {
            return new Direction[] {wallMountedSupportDirection(
                    state.get(Properties.BLOCK_FACE),
                    state.get(Properties.HORIZONTAL_FACING))};
        }

        if (state != null && state.contains(Properties.AXIS)) {
            return axisSupportDirections(state.get(Properties.AXIS));
        }

        Direction clickedFace = desiredClickedFace(state);
        if (clickedFace != null) {
            Direction primarySupport = faceDirectedSupportDirection(clickedFace);
            if (state.getBlock() instanceof EndRodBlock) {
                return new Direction[] {primarySupport, primarySupport.getOpposite()};
            }
            return new Direction[] {primarySupport};
        }

        if (state != null && state.getBlock() instanceof net.minecraft.block.TrapdoorBlock) {
            Direction facing = state.get(Properties.HORIZONTAL_FACING);
            return new Direction[] {
                    trapdoorSupportDirection(facing),
                    Direction.DOWN,
                    Direction.UP
            };
        }

        // По умолчанию приоритет граней со стороны, чтобы избежать взгляда строго вниз при движении вперед
        return new Direction[] {
                Direction.NORTH,
                Direction.SOUTH,
                Direction.EAST,
                Direction.WEST,
                Direction.DOWN,   // блок снизу (ставим сверху на него)
                Direction.UP      // блок сверху (ставим снизу — редко)
        };
    }

    public Direction[] getPlacementSupportDirections(BlockState state) {
        if (state != null && state.getBlock() instanceof SlabBlock) {
            return slabSupportDirections(state.get(Properties.SLAB_TYPE));
        }
        if (isWallMounted(state)) {
            return new Direction[] {wallMountedSupportDirection(
                    state.get(Properties.BLOCK_FACE),
                    state.get(Properties.HORIZONTAL_FACING))};
        }
        Direction clickedFace = desiredClickedFace(state);
        if (clickedFace != null) {
            return new Direction[] {faceDirectedSupportDirection(clickedFace)};
        }
        return getFacePriority(state).clone();
    }

    static Direction[] axisSupportDirections(Direction.Axis axis) {
        return switch (axis) {
            case X -> new Direction[] {Direction.WEST, Direction.EAST};
            case Y -> new Direction[] {Direction.DOWN, Direction.UP};
            case Z -> new Direction[] {Direction.NORTH, Direction.SOUTH};
        };
    }

    static Direction trapdoorSupportDirection(Direction desiredFacing) {
        // TrapdoorBlock copies the clicked horizontal face into FACING. The
        // support lies on the opposite side of that face.
        return desiredFacing.getOpposite();
    }

    static Direction faceDirectedSupportDirection(Direction desiredFacing) {
        return desiredFacing.getOpposite();
    }

    static Direction[] slabSupportDirections(SlabType type) {
        Direction[] horizontal = {
                Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
        };
        if (type == SlabType.DOUBLE) {
            return new Direction[] {
                    Direction.NORTH, Direction.SOUTH, Direction.EAST,
                    Direction.WEST, Direction.DOWN, Direction.UP
            };
        }
        Direction vertical = type == SlabType.TOP ? Direction.UP : Direction.DOWN;
        return new Direction[] {
                vertical, horizontal[0], horizontal[1], horizontal[2], horizontal[3]
        };
    }

    static Direction wallMountedSupportDirection(
            BlockFace face,
            Direction desiredFacing
    ) {
        return switch (face) {
            case FLOOR -> Direction.DOWN;
            case CEILING -> Direction.UP;
            case WALL -> desiredFacing.getOpposite();
        };
    }

    static Direction endRodFacingAfterClick(
            Direction clickedFace,
            boolean supportIsEndRod,
            Direction supportFacing
    ) {
        return supportIsEndRod && supportFacing == clickedFace
                ? clickedFace.getOpposite()
                : clickedFace;
    }

    /** Слот хотбара для creative-fill (последний слот) */
    private static final int CREATIVE_FILL_SLOT = 8;

    public BlockPlacer(MinecraftClient client) {
        this.client = client;
    }

    private com.humanbuilder.executor.BuildExecutor executor;

    public void setExecutor(com.humanbuilder.executor.BuildExecutor executor) {
        this.executor = executor;
    }

    public boolean isPlacedSchematicSupport(BlockPos pos) {
        return executor != null && executor.isPlacedSchematicBlock(pos);
    }

    public void setLineOfSightRequired(boolean lineOfSightRequired) {
        this.lineOfSightRequired = lineOfSightRequired;
    }

    // ════════════════════════════════════════════════════════════════════
    //  Публичный API
    // ════════════════════════════════════════════════════════════════════

    /**
     * Попытка поставить блок в указанную позицию.
     *
     * @param targetPos позиция, куда нужно поставить блок
     * @param state     желаемое состояние блока (нужен его Item)
     * @return true, если блок успешно «кликнут» (пакет отправлен)
     */
    public boolean placeBlock(BlockPos targetPos, BlockState state) {
        ClientPlayerEntity player = client.player;
        ClientPlayerInteractionManager im = client.interactionManager;
        if (player == null || im == null) return false;

        // OPEN is a post-placement action. Keep it in the same executor task so
        // the next block cannot start before the server confirms the final state.
        BlockState actual = client.world.getBlockState(targetPos);
        if (canToggleOpenState(actual, state)) {
            BlockHitResult hitResult = findToggleTarget(targetPos, actual, player.getEyePos());
            if (hitResult != null) {
                double dx = hitResult.getPos().x - player.getX();
                double dy = hitResult.getPos().y - player.getEyeY();
                double dz = hitResult.getPos().z - player.getZ();
                double dxz = Math.sqrt(dx * dx + dz * dz);
                float calculatedYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
                float targetYaw = player.getYaw() + MathHelper.wrapDegrees(calculatedYaw - player.getYaw());
                float targetPitch = (float) (-Math.toDegrees(Math.atan2(dy, dxz)));
                targetPitch = MathHelper.clamp(targetPitch, -90.0f, 90.0f);

                player.setYaw(targetYaw);
                player.setPitch(targetPitch);
                if (client.getNetworkHandler() != null) {
                    client.getNetworkHandler().sendPacket(new net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket.LookAndOnGround(
                            targetYaw, targetPitch, player.isOnGround(), player.horizontalCollision
                    ));
                }

                ActionResult result = im.interactBlock(player, Hand.MAIN_HAND, hitResult);
                if (result.isAccepted()) {
                    player.swingHand(Hand.MAIN_HAND);
                    return true;
                }
            }
            return false;
        }

        if (wouldPlacementIntersectPlayer(targetPos, state)) return false;

        // ── 1. Переключить хотбар на нужный блок ─────────────────────
        if (!switchToBlock(state)) {
            return false; // блока нет в хотбаре
        }

        // ── 2. Найти опорный блок и грань для клика ──────────────────
        clearPlacementObstruction();
        BlockHitResult hitResult = findPlacementTarget(targetPos, state);
        if (hitResult == null) {
            return false; // некуда «кликнуть» (нет соседнего блока)
        }

        // ── 3. Установить блок через interactionManager ──────────────
        // Snap player rotation to look exactly at the hit target to pass server-side angle validations
        double dx = hitResult.getPos().x - player.getX();
        double dy = hitResult.getPos().y - player.getEyeY();
        double dz = hitResult.getPos().z - player.getZ();
        double dxz = Math.sqrt(dx * dx + dz * dz);
        float calculatedYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float targetYaw = player.getYaw() + MathHelper.wrapDegrees(calculatedYaw - player.getYaw());

        // Clamp targetYaw for block facing state:
        // For StairsBlock, facing ALWAYS depends on player yaw (getPlayerFacing().getOpposite())!
        // For other directional blocks (like trapdoors), player yaw clamp is only used when clicking top/bottom faces.
        if (requiresPlacementFacing(state)) {
            if (state.contains(Properties.ROTATION)) {
                targetYaw = getPlacementYaw(state);
            } else if (state.getBlock() instanceof StairsBlock
                    || hitResult.getSide().getAxis().isVertical()) {
                float desiredYaw = getPlacementYaw(state);
                float diff = MathHelper.wrapDegrees(targetYaw - desiredYaw);
                diff = MathHelper.clamp(diff, -44.0f, 44.0f);
                targetYaw = desiredYaw + diff;
            }
        }

        float targetPitch = (float) (-Math.toDegrees(Math.atan2(dy, dxz)));
        targetPitch = MathHelper.clamp(targetPitch, -90.0f, 90.0f);

        player.setYaw(targetYaw);
        player.setPitch(targetPitch);
        if (client.getNetworkHandler() != null) {
            client.getNetworkHandler().sendPacket(new net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket.LookAndOnGround(
                    targetYaw, targetPitch, player.isOnGround(), player.horizontalCollision
            ));
        }

        boolean needSneak = isInteractableSupport(hitResult.getBlockPos());
        if (needSneak && client.getNetworkHandler() != null) {
            client.getNetworkHandler().sendPacket(new net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket(
                    player, net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket.Mode.PRESS_SHIFT_KEY
            ));
        }

        ActionResult result = im.interactBlock(player, Hand.MAIN_HAND, hitResult);

        if (needSneak && client.getNetworkHandler() != null) {
            client.getNetworkHandler().sendPacket(new net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket(
                    player, net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket.Mode.RELEASE_SHIFT_KEY
            ));
        }

        if (!result.isAccepted()) {
            if (player.getMainHandStack().getItem() instanceof net.minecraft.item.BucketItem) {
                result = im.interactItem(player, Hand.MAIN_HAND);
            }
        }
        if (!result.isAccepted()) {
            return false;
        }
        player.swingHand(Hand.MAIN_HAND); // анимация руки

        return true;
    }

    private boolean isInteractableSupport(BlockPos pos) {
        if (client.world == null) return false;
        BlockState supportState = client.world.getBlockState(pos);
        net.minecraft.block.Block b = supportState.getBlock();
        return b instanceof net.minecraft.block.TrapdoorBlock
                || b instanceof net.minecraft.block.DoorBlock
                || b instanceof net.minecraft.block.FenceGateBlock
                || b instanceof net.minecraft.block.ChestBlock
                || b instanceof net.minecraft.block.EnderChestBlock
                || b instanceof net.minecraft.block.BarrelBlock
                || b instanceof net.minecraft.block.FurnaceBlock
                || b instanceof net.minecraft.block.BlastFurnaceBlock
                || b instanceof net.minecraft.block.SmokerBlock
                || b instanceof net.minecraft.block.AnvilBlock
                || b instanceof net.minecraft.block.CraftingTableBlock
                || b instanceof net.minecraft.block.HopperBlock
                || b instanceof net.minecraft.block.DispenserBlock
                || b instanceof net.minecraft.block.DropperBlock
                || b instanceof net.minecraft.block.ShulkerBoxBlock
                || b instanceof net.minecraft.block.ButtonBlock
                || b instanceof net.minecraft.block.LeverBlock;
    }

    /**
     * Проверяет, удерживает ли игрок нужный блок в руке.
     */
    public boolean isReady(BlockState state) {
        ClientPlayerEntity player = client.player;
        if (player == null) return false;
        ItemStack stack = player.getMainHandStack();
        if (state.getBlock() == Blocks.WATER) {
            return stack.isOf(net.minecraft.item.Items.WATER_BUCKET);
        }
        if (state.getBlock() == Blocks.LAVA) {
            return stack.isOf(net.minecraft.item.Items.LAVA_BUCKET);
        }
        return !stack.isEmpty() && stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() == state.getBlock();
    }

    /**
     * Переключает хотбар на слот с нужным блоком.
     * Если блока нет в хотбаре — автоматически берёт из Creative инвентаря.
     *
     * @return true, если блок теперь выбран для руки (начат забор/переключение)
     */
    public boolean switchToBlock(BlockState state) {
        ClientPlayerEntity player = client.player;
        if (player == null) return false;

        net.minecraft.item.Item expectedItem = null;
        if (state.getBlock() == Blocks.WATER) {
            expectedItem = net.minecraft.item.Items.WATER_BUCKET;
        } else if (state.getBlock() == Blocks.LAVA) {
            expectedItem = net.minecraft.item.Items.LAVA_BUCKET;
        }

        // ── 1. Ищем блок/ведро в 9 слотах хотбара ──────────────────────────
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.getInventory().getStack(slot);
            if (!stack.isEmpty()) {
                boolean match = false;
                if (expectedItem != null) {
                    match = stack.isOf(expectedItem);
                } else if (stack.getItem() instanceof BlockItem blockItem) {
                    match = blockItem.getBlock() == state.getBlock();
                }
                if (match) {
                    if (player.getInventory().selectedSlot != slot) {
                        selectSlot(slot);
                    }
                    return true;
                }
            }
        }

        // ── 2. Блока нет → берём из Creative инвентаря ───────────────
        if (expectedItem != null) {
            return giveCreativeItem(expectedItem);
        }
        return giveCreativeItem(state);
    }

    private void selectSlot(int slot) {
        ClientPlayerEntity player = client.player;
        if (player != null) {
            player.getInventory().selectedSlot = slot;
            if (client.getNetworkHandler() != null) {
                client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
            }
        }
    }

    /**
     * Проверяет, доступен ли нужный блок.
     * В Creative Mode всегда true (мы можем взять любой блок).
     */
    public boolean hasBlockInHotbar(BlockState state) {
        ClientPlayerEntity player = client.player;
        if (player == null) return false;

        // В Creative Mode — всегда можем взять блок
        if (player.getAbilities().creativeMode) {
            return true;
        }

        net.minecraft.item.Item expectedItem = null;
        if (state.getBlock() == Blocks.WATER) {
            expectedItem = net.minecraft.item.Items.WATER_BUCKET;
        } else if (state.getBlock() == Blocks.LAVA) {
            expectedItem = net.minecraft.item.Items.LAVA_BUCKET;
        }

        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.getInventory().getStack(slot);
            if (!stack.isEmpty()) {
                if (expectedItem != null) {
                    if (stack.isOf(expectedItem)) return true;
                } else if (stack.getItem() instanceof BlockItem blockItem) {
                    if (blockItem.getBlock() == state.getBlock()) return true;
                }
            }
        }
        return false;
    }

    // ════════════════════════════════════════════════════════════════════
    //  Creative Mode — автозабор блоков из инвентаря
    // ════════════════════════════════════════════════════════════════════

    /**
     * Кладёт нужный предмет в хотбар через Creative-пакет.
     */
    private boolean giveCreativeItem(net.minecraft.item.Item item) {
        ClientPlayerEntity player = client.player;
        if (player == null || !player.getAbilities().creativeMode) {
            return false;
        }

        ItemStack targetStack = new ItemStack(item);
        if (targetStack.isEmpty()) {
            return false;
        }

        targetStack.setCount(targetStack.getMaxCount());

        selectSlot(CREATIVE_FILL_SLOT);

        int screenSlot = 36 + CREATIVE_FILL_SLOT;
        if (client.getNetworkHandler() != null) {
            client.getNetworkHandler().sendPacket(
                    new CreativeInventoryActionC2SPacket(screenSlot, targetStack)
            );
        }

        player.getInventory().setStack(CREATIVE_FILL_SLOT, targetStack);

        return true;
    }

    private boolean giveCreativeItem(BlockState state) {
        return giveCreativeItem(state.getBlock().asItem());
    }

    /**
     * Возвращает Vec3d центра грани, на которую нужно смотреть для установки.
     * Используется CameraSmoother'ом для наведения прицела.
     */
    public Vec3d getPlacementLookTarget(BlockPos targetPos, BlockState state) {
        BlockHitResult hit = findPlacementTarget(targetPos, state);
        if (hit == null) {
            // Fallback: смотрим на центр целевой позиции
            return Vec3d.ofCenter(targetPos);
        }
        return hit.getPos();
    }

    /**
     * Returns whether the target has a reachable, visible support face from
     * the player's current position.
     */
    public boolean canPlaceFromCurrentPosition(BlockPos targetPos, BlockState state) {
        return findPlacementTarget(targetPos, state) != null;
    }

    /** Uses the same face selection as real placement from a hypothetical eye position. */
    public boolean canPlaceFrom(Vec3d eyePos, BlockPos targetPos, BlockState state) {
        lastProbeObstruction = null;
        return findPlacementTarget(eyePos, targetPos, state, false) != null;
    }

    public boolean breakBlock(BlockPos pos) {
        ClientPlayerEntity player = client.player;
        ClientPlayerInteractionManager im = client.interactionManager;
        if (player == null || im == null || client.world == null) return false;
        if (client.world.getBlockState(pos).isReplaceable()) return true;
        if (!canSafelyBreak(pos)) return false;

        lastProbeObstruction = null;
        BlockHitResult hit = getBreakHitResult(player.getEyePos(), pos);
        if (hit == null) return false;

        // attackBlock sends a sequenced START_DESTROY_BLOCK packet. Calling
        // interactionManager.breakBlock directly only mutates the client world,
        // so the server restores the old block a moment later.
        boolean accepted = im.attackBlock(pos, hit.getSide());
        if (accepted) player.swingHand(Hand.MAIN_HAND);
        return accepted;
    }

    public Vec3d getBreakLookTarget(BlockPos pos) {
        if (client.world == null) return Vec3d.ofCenter(pos);
        BlockState state = client.world.getBlockState(pos);
        VoxelShape shape = state.getOutlineShape(client.world, pos);
        if (shape.isEmpty()) return Vec3d.ofCenter(pos);
        Box box = shape.getBoundingBox();
        return new Vec3d(
                pos.getX() + (box.minX + box.maxX) * 0.5,
                pos.getY() + (box.minY + box.maxY) * 0.5,
                pos.getZ() + (box.minZ + box.maxZ) * 0.5
        );
    }

    public boolean canBreakFromCurrentPosition(BlockPos pos) {
        return client.player != null && canBreakFrom(client.player.getEyePos(), pos);
    }

    public boolean canBreakFrom(Vec3d eyePos, BlockPos pos) {
        if (client.world == null || client.player == null) return false;
        lastProbeObstruction = null;
        return getBreakHitResult(eyePos, pos) != null;
    }

    private BlockHitResult getBreakHitResult(Vec3d eyePos, BlockPos pos) {
        Vec3d hitPoint = getBreakLookTarget(pos);
        if (eyePos.distanceTo(hitPoint) > 4.5) return null;
        BlockHitResult result = client.world.raycast(new RaycastContext(
                eyePos, hitPoint, RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE, client.player
        ));
        if (result.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK
                && result.getBlockPos().equals(pos)) {
            return result;
        }
        if (result.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK
                && !result.getBlockPos().equals(pos)) {
            lastProbeObstruction = result.getBlockPos().toImmutable();
        }
        // Fallback simulated hit if line of sight is blocked by floor blocks
        Direction side = Direction.UP;
        if (eyePos.y < pos.getY()) {
            side = Direction.DOWN;
        }
        return new BlockHitResult(hitPoint, side, pos, false);
    }

    public void clearPlacementObstruction() {
        lastPlacementObstruction = null;
    }

    public BlockPos consumePlacementObstruction() {
        BlockPos obstruction = lastPlacementObstruction;
        lastPlacementObstruction = null;
        return obstruction;
    }

    public void clearProbeObstruction() {
        lastProbeObstruction = null;
    }

    public BlockPos consumeProbeObstruction() {
        BlockPos obstruction = lastProbeObstruction;
        lastProbeObstruction = null;
        return obstruction;
    }

    public boolean requiresPlacementFacing(BlockState state) {
        return state.contains(Properties.HORIZONTAL_FACING)
                || state.contains(Properties.ROTATION);
    }

    public float getPlacementYaw(BlockState state) {
        if (state.contains(Properties.ROTATION)) {
            float placementOffset = state.getBlock() instanceof SkullBlock ? 0.0f : 180.0f;
            return rotationPlacementYaw(
                    state.get(Properties.ROTATION), placementOffset);
        }
        if (state.getBlock() instanceof net.minecraft.block.TrapdoorBlock && state.contains(Properties.HORIZONTAL_FACING)) {
            return state.get(Properties.HORIZONTAL_FACING).getOpposite().getPositiveHorizontalDegrees();
        }
        return state.get(Properties.HORIZONTAL_FACING).getPositiveHorizontalDegrees();
    }

    static float rotationPlacementYaw(int rotation, float placementOffset) {
        return MathHelper.wrapDegrees(
                RotationPropertyHelper.toDegrees(rotation) - placementOffset);
    }

    /** Keeps cinematic tools from rotating the camera just to orient stairs. */
    public boolean isPlacementFacingSatisfied(BlockState state, float tolerance) {
        if (!requiresPlacementFacing(state)) return true;
        if (client.player == null) return false;
        return Math.abs(MathHelper.wrapDegrees(
                getPlacementYaw(state) - client.player.getYaw())) <= tolerance;
    }

    /**
     * Returns false when removing {@code supportPos} would detach a neighboring
     * crystal, torch, plant, hanging block, gravity block, or a modded block
     * that expresses the same rule through {@code canPlaceAt}.
     */
    public boolean canSafelyBreak(BlockPos supportPos) {
        return findDependentBlock(supportPos) == null;
    }

    public BlockPos getLastProtectedDependent() {
        return lastProtectedDependent;
    }

    private boolean isSensitiveBlock(BlockState state) {
        net.minecraft.block.Block block = state.getBlock();
        String name = net.minecraft.registry.Registries.BLOCK.getId(block).getPath().toLowerCase();
        return block instanceof net.minecraft.block.AmethystClusterBlock
            || block instanceof net.minecraft.block.PointedDripstoneBlock
            || block instanceof net.minecraft.block.TorchBlock
            || block instanceof net.minecraft.block.FallingBlock
            || name.contains("crystal")
            || name.contains("bud")
            || name.contains("cluster")
            || name.contains("dripstone");
    }

    private BlockPos findDependentBlock(BlockPos supportPos) {
        lastProtectedDependent = null;
        if (client.world == null) return null;

        WorldView withoutSupport = worldWithoutBlock(supportPos);
        for (Direction direction : Direction.values()) {
            BlockPos neighborPos = supportPos.offset(direction);
            BlockState neighbor = client.world.getBlockState(neighborPos);
            if (neighbor.isAir() || neighbor.isReplaceable()) continue;

            if (isSensitiveBlock(neighbor)) {
                boolean isAttached = false;
                if (direction == Direction.UP) {
                    isAttached = true;
                } else if (neighbor.contains(Properties.FACING) && neighbor.get(Properties.FACING) == direction.getOpposite()) {
                    isAttached = true;
                } else if (neighbor.contains(Properties.HORIZONTAL_FACING) && neighbor.get(Properties.HORIZONTAL_FACING) == direction.getOpposite()) {
                    isAttached = true;
                }
                if (isAttached) {
                    lastProtectedDependent = neighborPos.toImmutable();
                    return lastProtectedDependent;
                }
            }

            if (direction == Direction.UP && neighbor.getBlock() instanceof FallingBlock) {
                lastProtectedDependent = neighborPos.toImmutable();
                return lastProtectedDependent;
            }

            try {
                boolean currentlyStable = neighbor.canPlaceAt(client.world, neighborPos);
                boolean stableWithoutSupport = neighbor.canPlaceAt(withoutSupport, neighborPos);
                if (currentlyStable && !stableWithoutSupport) {
                    lastProtectedDependent = neighborPos.toImmutable();
                    return lastProtectedDependent;
                }
            } catch (RuntimeException exception) {
                // A modded support rule that cannot be simulated is treated
                // conservatively: preserving the neighbor is safer than deleting it.
                HumanBuilderMod.LOGGER.warn(
                        "[HumanBuilder] Could not simulate support removal at {} for {}; protecting it",
                        supportPos.toShortString(), neighborPos.toShortString(), exception);
                lastProtectedDependent = neighborPos.toImmutable();
                return lastProtectedDependent;
            }
        }
        return null;
    }

    private WorldView worldWithoutBlock(BlockPos removedPos) {
        return (WorldView) Proxy.newProxyInstance(
                WorldView.class.getClassLoader(),
                new Class<?>[] { WorldView.class },
                (proxy, method, args) -> {
                    if ("getBlockState".equals(method.getName())
                            && args != null && args.length == 1
                            && removedPos.equals(args[0])) {
                        return Blocks.AIR.getDefaultState();
                    }
                    if ("getFluidState".equals(method.getName())
                            && args != null && args.length == 1
                            && removedPos.equals(args[0])) {
                        return Fluids.EMPTY.getDefaultState();
                    }
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "WorldViewWithout(" + removedPos.toShortString() + ")";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> method.invoke(this, args);
                        };
                    }
                    try {
                        return method.invoke(client.world, args);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                }
        );
    }

    /** States in one movement batch must not require a different placement gesture. */
    public boolean canBatchPlacementStates(BlockState first, BlockState second) {
        if (first.getBlock() != second.getBlock()) return false;
        if (first.getBlock() instanceof StairsBlock) {
            return first.get(Properties.HORIZONTAL_FACING) == second.get(Properties.HORIZONTAL_FACING)
                    && first.get(Properties.BLOCK_HALF) == second.get(Properties.BLOCK_HALF);
        }
        if (first.getBlock() instanceof SlabBlock) {
            return first.get(Properties.SLAB_TYPE) == second.get(Properties.SLAB_TYPE);
        }
        return first.equals(second);
    }

    /** True when the desired block's real collision shape overlaps the player. */
    public boolean wouldPlacementIntersectPlayer(BlockPos targetPos, BlockState state) {
        if (client.player == null) return false;
        return wouldPlacementIntersectBox(targetPos, state, client.player.getBoundingBox());
    }

    public boolean wouldPlacementIntersectBox(BlockPos targetPos, BlockState state, Box box) {
        if (client.world == null) return false;
        VoxelShape collision = state.getCollisionShape(client.world, targetPos);
        for (Box localBox : collision.getBoundingBoxes()) {
            Box worldBox = localBox.offset(
                    targetPos.getX(), targetPos.getY(), targetPos.getZ());
            if (box.intersects(worldBox)) return true;
        }
        return false;
    }

    /** Compares properties that are directly controlled during placement. */
    public boolean matchesPlacementState(BlockState actual, BlockState desired) {
        if (actual.getBlock() != desired.getBlock()) {
            if (desired.getBlock() == Blocks.DIRT
                    && (actual.getBlock() == Blocks.GRASS_BLOCK
                    || actual.getBlock() == Blocks.MYCELIUM)) {
                return true;
            }
            return false;
        }
        if (!managedPropertiesMatch(actual, desired, false)) return false;
        return !isManuallyToggleable(desired)
                || actual.get(Properties.OPEN) == desired.get(Properties.OPEN);
    }

    public boolean canCompletePlacementState(BlockState actual, BlockState desired) {
        if (desired.getBlock() instanceof SlabBlock
                && actual.getBlock() == desired.getBlock()
                && desired.get(Properties.SLAB_TYPE) == SlabType.DOUBLE
                && actual.get(Properties.SLAB_TYPE) != SlabType.DOUBLE) {
            return true;
        }

        return canToggleOpenState(actual, desired);
    }

    public boolean requiresPlacementVerification(BlockState state) {
        return state.getBlock() instanceof StairsBlock
                || state.getBlock() instanceof SlabBlock
                || hasManagedState(state)
                || isManuallyToggleable(state);
    }

    private boolean hasManagedState(BlockState state) {
        for (Property<?> property : state.getEntries().keySet()) {
            if (MANAGED_STATE_PROPERTIES.contains(property.getName())) return true;
        }
        return false;
    }

    private boolean managedPropertiesMatch(
            BlockState actual,
            BlockState desired,
            boolean ignoreOpen
    ) {
        Map<Property<?>, Comparable<?>> actualEntries = actual.getEntries();
        for (Map.Entry<Property<?>, Comparable<?>> entry : desired.getEntries().entrySet()) {
            String name = entry.getKey().getName();
            if (!MANAGED_STATE_PROPERTIES.contains(name)) continue;
            if (ignoreOpen && "open".equals(name)) continue;
            if (!Objects.equals(actualEntries.get(entry.getKey()), entry.getValue())) return false;
        }
        return true;
    }

    private boolean isManuallyToggleable(BlockState state) {
        if (!state.contains(Properties.OPEN)) return false;
        net.minecraft.block.Block block = state.getBlock();
        if (block == Blocks.IRON_TRAPDOOR || block == Blocks.IRON_DOOR) return false;
        return block instanceof net.minecraft.block.TrapdoorBlock
                || block instanceof net.minecraft.block.DoorBlock
                || block instanceof net.minecraft.block.FenceGateBlock;
    }

    private boolean canToggleOpenState(BlockState actual, BlockState desired) {
        return actual.getBlock() == desired.getBlock()
                && isManuallyToggleable(actual)
                && isManuallyToggleable(desired)
                && managedPropertiesMatch(actual, desired, true)
                && actual.get(Properties.OPEN) != desired.get(Properties.OPEN);
    }

    // ════════════════════════════════════════════════════════════════════
    //  Внутренние методы
    // ════════════════════════════════════════════════════════════════════

    /**
     * Находит опорный блок и грань для клика.
     *
     * Чтобы поставить блок в targetPos, нужно «кликнуть» на грань
     * соседнего твёрдого блока, которая смотрит в сторону targetPos.
     *
     * Дополнительно проверяет, что грань находится в пределах досягаемости
     * от глаз игрока (не через стену).
     */
    private BlockHitResult findPlacementTarget(BlockPos targetPos, BlockState state) {
        if (client.world == null || client.player == null) return null;
        // Every live-world probe owns its obstruction result. Without this,
        // a failed face from a previous tick can be broken after another face succeeds.
        clearPlacementObstruction();
        return findPlacementTarget(client.player.getEyePos(), targetPos, state, true);
    }

    private BlockHitResult findPlacementTarget(Vec3d eyePos, BlockPos targetPos, BlockState state,
                                               boolean recordPlacementObstruction) {
        if (client.world == null || client.player == null) return null;

        BlockState currentState = client.world.getBlockState(targetPos);
        if (canToggleOpenState(currentState, state)) {
            return findToggleTarget(targetPos, currentState, eyePos);
        }

        BlockHitResult slabMerge = findSlabMergeTarget(eyePos, targetPos, state);
        if (slabMerge != null) return slabMerge;

        BlockHitResult fallback = null;
        for (Direction dir : getFacePriority(state)) {
            // Блок в направлении dir от цели (потенциальная опора)
            BlockPos supportPos = targetPos.offset(dir);

            BlockState supportState = client.world.getBlockState(supportPos);

            boolean isSolid = !supportState.isAir() && !supportState.isReplaceable();
            boolean isRecentlyPlaced = isPlacedSchematicSupport(supportPos);

            // Проверяем, что опорный блок реально может принять клик.
            if (!isSolid && !isRecentlyPlaced) continue;
            if (state.getBlock() instanceof EndRodBlock && !isSolid) continue;

            // Грань опорного блока, на которую кликаем:
            // это грань, смотрящая ОТ опорного блока К целевому
            Direction clickFace = dir.getOpposite();
            if (!producesDesiredClickedFacing(state, supportState, clickFace)) continue;

            VoxelShape shape = supportState.getOutlineShape(client.world, supportPos);
            List<Box> boxes = shape.isEmpty() ? List.of(new Box(0, 0, 0, 1, 1, 1)) : shape.getBoundingBoxes();
            for (Box box : boxes) {
                Vec3d hitVec = faceCenter(supportPos, box, clickFace);
                hitVec = adjustHitForState(hitVec, box, supportPos, targetPos, clickFace, state);
                if (!producesDesiredHalf(hitVec, targetPos, clickFace, state)) continue;
                if (eyePos.distanceTo(hitVec) > 4.5) continue;

                if (fallback == null) {
                    fallback = new BlockHitResult(hitVec, clickFace, supportPos, false);
                }

                // Move the ray endpoint just inside the shape. Ending exactly on
                // a face intermittently produces MISS because of floating point rounding.
                Vec3d rayEnd = hitVec.add(
                        -clickFace.getOffsetX() * 0.01,
                        -clickFace.getOffsetY() * 0.01,
                        -clickFace.getOffsetZ() * 0.01
                );
                BlockHitResult result = client.world.raycast(new RaycastContext(
                        eyePos, rayEnd, RaycastContext.ShapeType.OUTLINE,
                        RaycastContext.FluidHandling.NONE, client.player
                ));
                if (result.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK
                        && result.getBlockPos().equals(supportPos)) {
                    Direction sideToUse = (result.getSide() == clickFace
                            || producesDesiredClickedFacing(state, supportState, result.getSide()))
                            ? result.getSide()
                            : clickFace;
                    if (producesDesiredHalf(hitVec, targetPos, sideToUse, state)) {
                        return new BlockHitResult(hitVec, sideToUse, supportPos, false);
                    }
                }
                if (result.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK
                        && !result.getBlockPos().equals(supportPos)
                        && !result.getBlockPos().equals(targetPos)) {
                    if (recordPlacementObstruction) {
                        lastPlacementObstruction = result.getBlockPos().toImmutable();
                    } else {
                        lastProbeObstruction = result.getBlockPos().toImmutable();
                    }
                }
            }
        }

        if (lineOfSightRequired) {
            return null;
        }
        return fallback; // Нет подходящей опоры
    }

    private Direction desiredClickedFace(BlockState state) {
        if (state == null) return null;
        net.minecraft.block.Block block = state.getBlock();
        if ((block instanceof RodBlock || block instanceof AmethystClusterBlock)
                && state.contains(Properties.FACING)) {
            return state.get(Properties.FACING);
        }
        if ((block instanceof WallTorchBlock
                || block instanceof LadderBlock
                || block instanceof WallSignBlock
                || block instanceof WallBannerBlock
                || block instanceof WallSkullBlock)
                && state.contains(Properties.HORIZONTAL_FACING)) {
            return state.get(Properties.HORIZONTAL_FACING);
        }
        return null;
    }

    private boolean producesDesiredClickedFacing(
            BlockState desired,
            BlockState support,
            Direction clickedFace
    ) {
        if (isWallMounted(desired)) {
            Direction supportDirection = wallMountedSupportDirection(
                    desired.get(Properties.BLOCK_FACE),
                    desired.get(Properties.HORIZONTAL_FACING));
            return clickedFace == supportDirection.getOpposite();
        }

        Direction desiredFacing = desiredClickedFace(desired);
        if (desiredFacing == null) return true;

        Direction produced = clickedFace;
        if (desired.getBlock() instanceof EndRodBlock) {
            boolean supportIsEndRod = support.getBlock() instanceof EndRodBlock;
            Direction supportFacing = supportIsEndRod && support.contains(Properties.FACING)
                    ? support.get(Properties.FACING) : null;
            produced = endRodFacingAfterClick(
                    clickedFace, supportIsEndRod, supportFacing);
        }
        return produced == desiredFacing;
    }

    private boolean isWallMounted(BlockState state) {
        return state != null
                && state.getBlock() instanceof WallMountedBlock
                && state.contains(Properties.BLOCK_FACE)
                && state.contains(Properties.HORIZONTAL_FACING);
    }

    private Vec3d faceCenter(BlockPos pos, Box box, Direction face) {
        double x = (box.minX + box.maxX) * 0.5;
        double y = (box.minY + box.maxY) * 0.5;
        double z = (box.minZ + box.maxZ) * 0.5;

        switch (face) {
            case EAST -> x = box.maxX;
            case WEST -> x = box.minX;
            case UP -> y = box.maxY;
            case DOWN -> y = box.minY;
            case SOUTH -> z = box.maxZ;
            case NORTH -> z = box.minZ;
        }
        return new Vec3d(pos.getX() + x, pos.getY() + y, pos.getZ() + z);
    }

    private Vec3d adjustHitForState(Vec3d hit, Box supportBox, BlockPos supportPos,
                                    BlockPos targetPos, Direction face, BlockState state) {
        if (face.getAxis().isVertical()) {
            return hit;
        }

        Boolean top = desiredTopHalf(state);
        if (top == null) return hit;

        double desiredY = targetPos.getY() + (top ? 0.75 : 0.25);
        double minY = supportPos.getY() + supportBox.minY + 0.02;
        double maxY = supportPos.getY() + supportBox.maxY - 0.02;

        if (top && maxY <= targetPos.getY() + 0.5) {
            return hit;
        }
        if (!top && minY >= targetPos.getY() + 0.5) {
            return hit;
        }

        return new Vec3d(hit.x, Math.max(minY, Math.min(maxY, desiredY)), hit.z);
    }

    private boolean producesDesiredHalf(Vec3d hit, BlockPos targetPos,
                                        Direction face, BlockState state) {
        Boolean desiredTop = desiredTopHalf(state);
        if (desiredTop == null) return true;

        boolean producesTop = face == Direction.DOWN
                || face != Direction.UP && hit.y - targetPos.getY() > 0.5;
        return producesTop == desiredTop;
    }

    private Boolean desiredTopHalf(BlockState state) {
        if (state.getBlock() instanceof StairsBlock && state.contains(Properties.BLOCK_HALF)) {
            return state.get(Properties.BLOCK_HALF) == BlockHalf.TOP;
        }
        if (state.getBlock() instanceof SlabBlock && state.contains(Properties.SLAB_TYPE)) {
            SlabType type = state.get(Properties.SLAB_TYPE);
            if (type != SlabType.DOUBLE) return type == SlabType.TOP;
        }
        if (state.getBlock() instanceof net.minecraft.block.TrapdoorBlock && state.contains(net.minecraft.block.TrapdoorBlock.HALF)) {
            return state.get(net.minecraft.block.TrapdoorBlock.HALF) == net.minecraft.block.enums.BlockHalf.TOP;
        }
        return null;
    }

    private BlockHitResult findSlabMergeTarget(Vec3d eyePos, BlockPos targetPos, BlockState desired) {
        if (!(desired.getBlock() instanceof SlabBlock)
                || desired.get(Properties.SLAB_TYPE) != SlabType.DOUBLE) {
            return null;
        }

        BlockState current = client.world.getBlockState(targetPos);
        if (current.getBlock() != desired.getBlock()) return null;
        SlabType currentType = current.get(Properties.SLAB_TYPE);
        if (currentType == SlabType.DOUBLE) return null;

        Direction clickFace = currentType == SlabType.BOTTOM ? Direction.UP : Direction.DOWN;
        VoxelShape shape = current.getOutlineShape(client.world, targetPos);
        for (Box box : shape.getBoundingBoxes()) {
            Vec3d hit = faceCenter(targetPos, box, clickFace);
            if (eyePos.distanceTo(hit) > 4.5) continue;
            Vec3d rayEnd = hit.add(
                    -clickFace.getOffsetX() * 0.01,
                    -clickFace.getOffsetY() * 0.01,
                    -clickFace.getOffsetZ() * 0.01
            );
            BlockHitResult result = client.world.raycast(new RaycastContext(
                    eyePos, rayEnd, RaycastContext.ShapeType.OUTLINE,
                    RaycastContext.FluidHandling.NONE, client.player
            ));
            if (result.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK
                    && result.getBlockPos().equals(targetPos)) {
                Direction sideToUse = result.getSide() != null ? result.getSide() : clickFace;
                return new BlockHitResult(hit, sideToUse, targetPos, false);
            }
            return new BlockHitResult(hit, clickFace, targetPos, false);
        }
        return null;
    }

    private BlockHitResult findToggleTarget(
            BlockPos targetPos,
            BlockState currentState,
            Vec3d eyePos
    ) {
        if (client.world == null || client.player == null) return null;
        VoxelShape shape = currentState.getOutlineShape(client.world, targetPos);

        Direction bestSide = null;
        Vec3d bestHit = null;
        double bestDist = Double.MAX_VALUE;

        for (Box box : shape.getBoundingBoxes()) {
            for (Direction side : Direction.values()) {
                Vec3d center = faceCenter(targetPos, box, side);
                double dist = eyePos.distanceTo(center);
                if (dist > 4.5 || dist >= bestDist) continue;
                Vec3d rayEnd = center.add(
                        -side.getOffsetX() * 0.01,
                        -side.getOffsetY() * 0.01,
                        -side.getOffsetZ() * 0.01
                );
                BlockHitResult ray = client.world.raycast(new RaycastContext(
                        eyePos, rayEnd, RaycastContext.ShapeType.OUTLINE,
                        RaycastContext.FluidHandling.NONE, client.player
                ));
                if (ray.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK
                        && ray.getBlockPos().equals(targetPos)) {
                    bestDist = dist;
                    bestHit = ray.getPos();
                    bestSide = ray.getSide();
                }
            }
        }

        if (bestHit != null && bestSide != null) {
            return new BlockHitResult(bestHit, bestSide, targetPos, false);
        }

        return null;
    }
}

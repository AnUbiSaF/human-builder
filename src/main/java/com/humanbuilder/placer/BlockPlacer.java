package com.humanbuilder.placer;

import net.minecraft.block.BlockState;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
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
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.RaycastContext;
import net.minecraft.state.property.Properties;

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

    private final MinecraftClient client;
    private BlockPos lastPlacementObstruction;
    private BlockPos lastProbeObstruction;

    /** Порядок проверки граней: приоритет снизу (как ставит человек) */
    private static final Direction[] FACE_PRIORITY = {
            Direction.DOWN,   // блок снизу (ставим сверху на него)
            Direction.NORTH,
            Direction.SOUTH,
            Direction.EAST,
            Direction.WEST,
            Direction.UP      // блок сверху (ставим снизу — редко)
    };

    /** Слот хотбара для creative-fill (последний слот) */
    private static final int CREATIVE_FILL_SLOT = 8;

    public BlockPlacer(MinecraftClient client) {
        this.client = client;
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
        ActionResult result = im.interactBlock(player, Hand.MAIN_HAND, hitResult);
        if (!result.isAccepted()) {
            return false;
        }
        player.swingHand(Hand.MAIN_HAND); // анимация руки

        return true;
    }

    /**
     * Проверяет, удерживает ли игрок нужный блок в руке.
     */
    public boolean isReady(BlockState state) {
        ClientPlayerEntity player = client.player;
        if (player == null) return false;
        ItemStack stack = player.getMainHandStack();
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

        // ── 1. Ищем блок в 9 слотах хотбара ──────────────────────────
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.getInventory().getStack(slot);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem blockItem) {
                if (blockItem.getBlock() == state.getBlock()) {
                    if (player.getInventory().selectedSlot != slot) {
                        selectSlot(slot);
                    }
                    return true;
                }
            }
        }

        // ── 2. Блока нет → берём из Creative инвентаря ───────────────
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

        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.getInventory().getStack(slot);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem blockItem) {
                if (blockItem.getBlock() == state.getBlock()) {
                    return true;
                }
            }
        }
        return false;
    }

    // ════════════════════════════════════════════════════════════════════
    //  Creative Mode — автозабор блоков из инвентаря
    // ════════════════════════════════════════════════════════════════════

    /**
     * Кладёт нужный блок в хотбар через Creative-пакет.
     *
     * Использует {@code CreativeInventoryActionC2SPacket}, который
     * сообщает серверу: «положить стак в слот N».
     * Screen handler slot ID для хотбара: 36 + hotbar_slot.
     *
     * @return true если блок успешно положен в руку
     */
    private boolean giveCreativeItem(BlockState state) {
        ClientPlayerEntity player = client.player;
        if (player == null || !player.getAbilities().creativeMode) {
            return false;
        }

        // Создаём стак нужного блока
        ItemStack targetStack = new ItemStack(state.getBlock().asItem());
        if (targetStack.isEmpty()) {
            return false; // блок без предмета (fire, и т.п.)
        }

        // Полный стак (64 шт.)
        targetStack.setCount(targetStack.getMaxCount());

        // Переключаемся на жертвенный слот с отправкой пакета на сервер
        selectSlot(CREATIVE_FILL_SLOT);

        // Отправляем серверу пакет: slot 36+8 = 44 → хотбар слот 8
        int screenSlot = 36 + CREATIVE_FILL_SLOT;
        if (client.getNetworkHandler() != null) {
            client.getNetworkHandler().sendPacket(
                    new CreativeInventoryActionC2SPacket(screenSlot, targetStack)
            );
        }

        // Обновляем локально (чтобы не ждать ответа сервера)
        player.getInventory().setStack(CREATIVE_FILL_SLOT, targetStack);

        return true;
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
                && !result.getBlockPos().equals(pos)) {
            lastProbeObstruction = result.getBlockPos().toImmutable();
        }
        return result.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK
                && result.getBlockPos().equals(pos) ? result : null;
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
        return state.getBlock() instanceof StairsBlock && state.contains(Properties.HORIZONTAL_FACING);
    }

    public float getPlacementYaw(BlockState state) {
        return state.get(Properties.HORIZONTAL_FACING).getPositiveHorizontalDegrees();
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
        if (client.player == null || client.world == null) return false;
        Box playerBox = client.player.getBoundingBox();
        VoxelShape collision = state.getCollisionShape(client.world, targetPos);
        for (Box localBox : collision.getBoundingBoxes()) {
            Box worldBox = localBox.offset(
                    targetPos.getX(), targetPos.getY(), targetPos.getZ());
            if (playerBox.intersects(worldBox)) return true;
        }
        return false;
    }

    /** Compares properties that are directly controlled during placement. */
    public boolean matchesPlacementState(BlockState actual, BlockState desired) {
        if (actual.getBlock() != desired.getBlock()) return false;
        if (desired.getBlock() instanceof StairsBlock) {
            return actual.get(Properties.HORIZONTAL_FACING) == desired.get(Properties.HORIZONTAL_FACING)
                    && actual.get(Properties.BLOCK_HALF) == desired.get(Properties.BLOCK_HALF);
        }
        if (desired.getBlock() instanceof SlabBlock) {
            return actual.get(Properties.SLAB_TYPE) == desired.get(Properties.SLAB_TYPE);
        }
        return true;
    }

    public boolean canCompletePlacementState(BlockState actual, BlockState desired) {
        return desired.getBlock() instanceof SlabBlock
                && actual.getBlock() == desired.getBlock()
                && desired.get(Properties.SLAB_TYPE) == SlabType.DOUBLE
                && actual.get(Properties.SLAB_TYPE) != SlabType.DOUBLE;
    }

    public boolean requiresPlacementVerification(BlockState state) {
        return state.getBlock() instanceof StairsBlock || state.getBlock() instanceof SlabBlock;
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

        BlockHitResult slabMerge = findSlabMergeTarget(eyePos, targetPos, state);
        if (slabMerge != null) return slabMerge;

        for (Direction dir : FACE_PRIORITY) {
            // Блок в направлении dir от цели (потенциальная опора)
            BlockPos supportPos = targetPos.offset(dir);

            BlockState supportState = client.world.getBlockState(supportPos);

            // Проверяем, что опорный блок реально может принять клик.
            if (supportState.isAir() || supportState.isReplaceable()) continue;

            // Грань опорного блока, на которую кликаем:
            // это грань, смотрящая ОТ опорного блока К целевому
            Direction clickFace = dir.getOpposite();

            VoxelShape shape = supportState.getOutlineShape(client.world, supportPos);
            for (Box box : shape.getBoundingBoxes()) {
                Vec3d hitVec = faceCenter(supportPos, box, clickFace);
                hitVec = adjustHitForState(hitVec, box, supportPos, targetPos, clickFace, state);
                if (!producesDesiredHalf(hitVec, targetPos, clickFace, state)) continue;
                if (eyePos.distanceTo(hitVec) > 4.5) continue;

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
                        && result.getBlockPos().equals(supportPos)
                        && result.getSide() == clickFace) {
                    return new BlockHitResult(result.getPos(), clickFace, supportPos, false);
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

        return null; // Нет подходящей опоры
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
                    && result.getBlockPos().equals(targetPos)
                    && result.getSide() == clickFace) {
                return new BlockHitResult(result.getPos(), clickFace, targetPos, false);
            }
        }
        return null;
    }
}

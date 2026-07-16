package com.humanbuilder.placer;

import net.minecraft.block.BlockState;
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
import net.minecraft.util.math.Vec3d;

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

        // ── 1. Переключить хотбар на нужный блок ─────────────────────
        if (!switchToBlock(state)) {
            return false; // блока нет в хотбаре
        }

        // ── 2. Найти опорный блок и грань для клика ──────────────────
        BlockHitResult hitResult = findPlacementTarget(targetPos);
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
    public Vec3d getPlacementLookTarget(BlockPos targetPos) {
        BlockHitResult hit = findPlacementTarget(targetPos);
        if (hit == null) {
            // Fallback: смотрим на центр целевой позиции
            return Vec3d.ofCenter(targetPos);
        }
        return hit.getPos();
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
     * Пример: чтобы поставить блок на координату (5, 2, 3),
     * кликаем на ВЕРХНЮЮ грань блока (5, 1, 3) — если он существует.
     */
    private BlockHitResult findPlacementTarget(BlockPos targetPos) {
        if (client.world == null) return null;

        for (Direction dir : FACE_PRIORITY) {
            // Блок в направлении dir от цели (потенциальная опора)
            BlockPos supportPos = targetPos.offset(dir);

            BlockState supportState = client.world.getBlockState(supportPos);

            // Проверяем, что опорный блок реально может принять клик.
            if (supportState.isAir() || supportState.isReplaceable()) continue;

            // Грань опорного блока, на которую кликаем:
            // это грань, смотрящая ОТ опорного блока К целевому
            Direction clickFace = dir.getOpposite();

            // Точка клика — центр грани
            Vec3d hitVec = Vec3d.ofCenter(supportPos).add(
                    clickFace.getOffsetX() * 0.5,
                    clickFace.getOffsetY() * 0.5,
                    clickFace.getOffsetZ() * 0.5
            );

            return new BlockHitResult(hitVec, clickFace, supportPos, false);
        }

        return null; // Нет подходящей опоры
    }
}

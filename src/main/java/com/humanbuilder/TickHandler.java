package com.humanbuilder;

import com.humanbuilder.camera.CameraSmoother;
import com.humanbuilder.executor.BuildExecutor;
import com.humanbuilder.movement.MovementController;
import net.minecraft.client.MinecraftClient;

/**
 * Обработчик клиентских тиков.
 *
 * Подписывается на {@code ClientTickEvents.END_CLIENT_TICK} и каждый тик
 * (50 мс) вызывает tick() у всех активных модулей в правильном порядке:
 *
 *   1. {@link BuildExecutor#tickPreview()} — обновляет позицию голограммы (если превью)
 *   2. {@link BuildExecutor#tick()} — стейт-машина принимает решения
 *   3. {@link MovementController#tick()} — обновляет клавиши движения
 *   4. {@link CameraSmoother#tick()} — интерполирует камеру
 *
 * Порядок важен: сначала executor решает, куда идти/смотреть,
 * затем movement и camera исполняют эти решения в том же тике.
 */
public class TickHandler {

    private final CameraSmoother camera;
    private final MovementController movement;
    private final BuildExecutor executor;

    public TickHandler(CameraSmoother camera, MovementController movement, BuildExecutor executor) {
        this.camera = camera;
        this.movement = movement;
        this.executor = executor;
    }

    /**
     * Вызывается каждый тик (END_CLIENT_TICK).
     * Ничего не делает, если мод неактивен (executor в IDLE).
     */
    public void onEndTick(MinecraftClient client) {
        // Не работаем, если нет игрока или мира
        if (client.player == null || client.world == null) return;

        // ── 0. Превью — перемещение голограммы за прицелом ────────────
        executor.tickPreview();

        // ── 1. Стейт-машина — решения ────────────────────────────────
        executor.tick();

        // ── 2. Движение — исполнение ─────────────────────────────────
        movement.tick();

        // ── 3. Камера — интерполяция ─────────────────────────────────
        camera.tick();
    }
}

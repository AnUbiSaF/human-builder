package com.humanbuilder;

import com.humanbuilder.camera.CameraSmoother;
import com.humanbuilder.command.BuildCommand;
import com.humanbuilder.executor.BuildExecutor;
import com.humanbuilder.movement.MovementController;
import com.humanbuilder.placer.BlockPlacer;
import com.humanbuilder.timing.HumanTiming;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Точка входа HumanBuilder мода.
 *
 * Инициализирует все модули и связывает их между собой.
 * Регистрирует обработчик тиков и команды чата.
 *
 * Мод работает исключительно на клиентской стороне (environment = "client").
 */
public class HumanBuilderMod implements ClientModInitializer {

    public static final String MOD_ID = "human-builder";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // ── Единственные экземпляры модулей (синглтоны) ────────────────────
    private static CameraSmoother camera;
    private static MovementController movement;
    private static BlockPlacer placer;
    private static HumanTiming timing;
    private static BuildExecutor executor;
    private static TickHandler tickHandler;

    @Override
    public void onInitializeClient() {
        LOGGER.info("[HumanBuilder] Инициализация мода...");

        MinecraftClient client = MinecraftClient.getInstance();

        // ── Создание модулей ─────────────────────────────────────────
        camera   = new CameraSmoother(client);
        timing   = new HumanTiming();
        placer   = new BlockPlacer(client);
        movement = new MovementController(client, camera);
        executor = new BuildExecutor(client, camera, movement, placer, timing);

        // ── Обработчик тиков ─────────────────────────────────────────
        tickHandler = new TickHandler(camera, movement, executor);

        ClientTickEvents.END_CLIENT_TICK.register(tickHandler::onEndTick);

        // ── Регистрация команд ───────────────────────────────────────
        BuildCommand.register(executor);

        LOGGER.info("[HumanBuilder] Мод успешно инициализирован!");
    }

    // ── Геттеры для доступа из других частей мода ─────────────────────

    public static CameraSmoother getCamera()     { return camera; }
    public static MovementController getMovement() { return movement; }
    public static BlockPlacer getPlacer()         { return placer; }
    public static HumanTiming getTiming()         { return timing; }
    public static BuildExecutor getExecutor()     { return executor; }
}

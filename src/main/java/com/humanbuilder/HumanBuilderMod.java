package com.humanbuilder;

import com.humanbuilder.camera.CameraSmoother;
import com.humanbuilder.command.BuildCommand;
import com.humanbuilder.executor.BuildExecutor;
import com.humanbuilder.executor.BuildState;
import com.humanbuilder.gui.HumanBuilderScreen;
import com.humanbuilder.movement.MovementController;
import com.humanbuilder.placer.BlockPlacer;
import com.humanbuilder.timing.HumanTiming;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.ActionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.lwjgl.glfw.GLFW;

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
    private static KeyBinding openGuiKey;

    @Override
    public void onInitializeClient() {
        LOGGER.info("[HumanBuilder] Инициализация мода...");

        MinecraftClient client = MinecraftClient.getInstance();

        // ── Создание модулей ─────────────────────────────────────────
        camera   = new CameraSmoother(client);
        timing   = new HumanTiming();
        placer   = new BlockPlacer(client);
        movement = new MovementController(client, camera, placer);
        executor = new BuildExecutor(client, camera, movement, placer, timing);

        // ── Рендерер голограммы ──────────────────────────────────────
        com.humanbuilder.renderer.SchematicRenderer renderer = new com.humanbuilder.renderer.SchematicRenderer(executor);
        renderer.register();

        // ── Обработчик тиков ─────────────────────────────────────────
        tickHandler = new TickHandler(camera, movement, executor);

        ClientTickEvents.END_CLIENT_TICK.register(tickHandler::onEndTick);

        openGuiKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.human-builder.open_gui",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_H,
                "key.category.human-builder"
        ));
        ClientTickEvents.END_CLIENT_TICK.register(currentClient -> {
            while (openGuiKey.wasPressed()) {
                if (currentClient.currentScreen == null) {
                    currentClient.setScreen(new HumanBuilderScreen(executor));
                }
            }
        });

        // ── Обработчик ПКМ палкой (фиксация голограммы) ──────────────
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            // Только клиентская сторона
            if (!world.isClient()) return ActionResult.PASS;

            // Только если в режиме превью и палка в руке
            if (executor.getState() != BuildState.PREVIEW || !executor.isStickMoveMode()) {
                return ActionResult.PASS;
            }

            var stack = player.getStackInHand(hand);
            if (stack.isEmpty()) return ActionResult.PASS;

            String itemId = net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).getPath();
            if (!"stick".equals(itemId)) return ActionResult.PASS;

            // Фиксируем голограмму на текущей позиции
            executor.lockHologram();
            return ActionResult.SUCCESS; // поглощаем клик
        });

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

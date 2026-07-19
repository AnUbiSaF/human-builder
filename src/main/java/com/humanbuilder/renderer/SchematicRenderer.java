package com.humanbuilder.renderer;

import com.humanbuilder.executor.BuildExecutor;
import com.humanbuilder.logic.BuildEntry;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * Рендерер голограммы схемы и интерактивных векторных инструментов.
 * Показывает только ближайшие позиции, чтобы большие фасады не снижали FPS.
 */
public class SchematicRenderer {

    private static final int RENDER_RADIUS = 16;
    private static final int MAX_RENDERED_BLOCKS = 768;
    private static final long CACHE_INTERVAL_NANOS = 300_000_000L;

    private final BuildExecutor executor;
    private List<BuildEntry> visibleCache = List.of();
    private long nextCacheRefresh;

    public SchematicRenderer(BuildExecutor executor) {
        this.executor = executor;
    }

    /**
     * Регистрирует обработчик рендеринга в Fabric API.
     */
    public void register() {
        WorldRenderEvents.LAST.register(this::render);
    }

    private void render(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) {
            visibleCache = List.of();
            return;
        }

        Vec3d cameraPos = context.camera().getPos();
        BlockPos playerPos = client.player.getBlockPos();
        long now = System.nanoTime();
        boolean schematicVisible = executor.isActive() && executor.isHologramVisible();
        if (schematicVisible && now >= nextCacheRefresh) {
            visibleCache = executor.getVisibleEntries(
                    playerPos, RENDER_RADIUS, MAX_RENDERED_BLOCKS);
            nextCacheRefresh = now + CACHE_INTERVAL_NANOS;
        } else if (!schematicVisible) {
            visibleCache = List.of();
        }
        if (visibleCache.isEmpty()) {
            return;
        }

        MatrixStack matrices = context.matrixStack();
        VertexConsumerProvider consumers = context.consumers();
        if (matrices == null || consumers == null) {
            return;
        }

        // Рендерим только блоки в радиусе 16 метров, чтобы не перегружать FPS
        for (BuildEntry entry : visibleCache) {
            BlockPos pos = entry.pos();

            // Выбираем цвет в зависимости от категории блока
            float r = 0.0f, g = 0.8f, b = 0.8f; // дефолтный бирюзовый
            switch (entry.category()) {
                case FOUNDATION:
                    r = 0.0f; g = 0.4f; b = 1.0f; // глубокий синий
                    break;
                case WALL:
                    r = 0.0f; g = 0.8f; b = 0.2f; // зеленый
                    break;
                case PILLAR:
                    r = 1.0f; g = 0.72f; b = 0.15f;
                    break;
                case ROOF:
                    r = 0.95f; g = 0.42f; b = 0.12f;
                    break;
                case WINDOW:
                    r = 0.2f; g = 0.65f; b = 1.0f;
                    break;
                case INTERIOR_WALL:
                    r = 0.0f; g = 0.8f; b = 0.8f; // бирюзовый
                    break;
                case DECOR:
                    r = 0.9f; g = 0.1f; b = 0.8f; // фиолетовый
                    break;
            }

            drawHologramBox(matrices, consumers, pos, cameraPos, r, g, b, 0.25f);
        }

    }

    private void drawHologramBox(
            MatrixStack matrices,
            VertexConsumerProvider consumers,
            BlockPos pos,
            Vec3d cameraPos,
            float r,
            float g,
            float b,
            float alpha
    ) {
        double x1 = pos.getX() - cameraPos.x;
        double y1 = pos.getY() - cameraPos.y;
        double z1 = pos.getZ() - cameraPos.z;
        double x2 = x1 + 1.0;
        double y2 = y1 + 1.0;
        double z2 = z1 + 1.0;

        // Рисуем красивый полупрозрачный куб с контуром с помощью DebugRenderer
        net.minecraft.client.render.debug.DebugRenderer.drawBox(
                matrices,
                consumers,
                x1, y1, z1,
                x2, y2, z2,
                r, g, b, alpha
        );
    }
}

package com.humanbuilder.command;

import com.humanbuilder.HumanBuilderMod;
import com.humanbuilder.executor.BuildExecutor;
import com.humanbuilder.parser.SchematicParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Команда чата для управления строительством.
 *
 * Использование:
 *   /humanbuilder build <файл.litematic>  — начать строительство
 *   /humanbuilder stop                    — остановить строительство
 *   /humanbuilder status                  — показать прогресс
 *
 * Путь к файлу ищется относительно папки schematics/ в .minecraft,
 * или принимается абсолютный путь.
 */
public class BuildCommand {

    private static BuildExecutor executor;

    /**
     * Регистрирует команды. Вызывается из HumanBuilderMod.
     */
    public static void register(BuildExecutor buildExecutor) {
        executor = buildExecutor;

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(
                ClientCommandManager.literal("humanbuilder")
                    // /humanbuilder build <filename>
                    .then(ClientCommandManager.literal("build")
                        .then(ClientCommandManager.argument("file", StringArgumentType.greedyString())
                            .executes(ctx -> executeBuild(
                                ctx.getSource(),
                                StringArgumentType.getString(ctx, "file")
                            ))
                        )
                    )
                    // /humanbuilder stop
                    .then(ClientCommandManager.literal("stop")
                        .executes(ctx -> executeStop(ctx.getSource()))
                    )
                    // /humanbuilder status
                    .then(ClientCommandManager.literal("status")
                        .executes(ctx -> executeStatus(ctx.getSource()))
                    )
            );
        });
    }

    // ════════════════════════════════════════════════════════════════════
    //  Обработчики команд
    // ════════════════════════════════════════════════════════════════════

    private static int executeBuild(FabricClientCommandSource source, String filename) {
        if (executor.isActive()) {
            source.sendFeedback(Text.literal("§c[HB] Строительство уже идёт! Используйте /humanbuilder stop"));
            return 0;
        }

        // ── Проверка Creative Mode ───────────────────────────────────
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null && !client.player.getAbilities().creativeMode) {
            source.sendFeedback(Text.literal("§c[HB] Ошибка: HumanBuilder работает только в Creative Mode!"));
            return 0;
        }

        // ── Поиск файла ──────────────────────────────────────────────
        File schematicFile = resolveSchematicFile(filename);
        if (schematicFile == null || !schematicFile.exists()) {
            source.sendFeedback(Text.literal("§c[HB] Файл не найден: " + filename));
            source.sendFeedback(Text.literal("§7    Путь: " + getSchematicsDir().getAbsolutePath()));
            return 0;
        }

        source.sendFeedback(Text.literal("§a[HB] Загрузка: §e" + schematicFile.getName() + "§a..."));

        // ── Парсинг в отдельном потоке (чтобы не фризить клиент) ─────
        // Для маленьких схем это мгновенно, но для больших — важно.
        try {
            SchematicParser parser = new SchematicParser();
            Map<BlockPos, BlockState> blocks = parser.parse(schematicFile);

            if (blocks.isEmpty()) {
                source.sendFeedback(Text.literal("§c[HB] Схема пуста или не содержит блоков."));
                return 0;
            }

            // Находим нижний ближний угол схемы (минимальные координаты)
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            for (BlockPos pos : blocks.keySet()) {
                if (pos.getX() < minX) minX = pos.getX();
                if (pos.getY() < minY) minY = pos.getY();
                if (pos.getZ() < minZ) minZ = pos.getZ();
            }

            // Смещаем схему перед игроком (на 3 блока вперед по горизонтали), чтобы игрок не мешал установке первого блока своим телом
            BlockPos playerPos = client.player != null ? client.player.getBlockPos() : BlockPos.ORIGIN;
            net.minecraft.util.math.Direction facing = client.player != null ? client.player.getHorizontalFacing() : net.minecraft.util.math.Direction.NORTH;
            BlockPos buildOrigin = playerPos.offset(facing, 3);

            Map<BlockPos, BlockState> offsetBlocks = new HashMap<>();
            for (var entry : blocks.entrySet()) {
                BlockPos relPos = entry.getKey();
                BlockPos absPos = relPos.add(buildOrigin.getX() - minX, buildOrigin.getY() - minY, buildOrigin.getZ() - minZ);
                offsetBlocks.put(absPos, entry.getValue());
            }

            source.sendFeedback(Text.literal("§a[HB] Загружено §e" + blocks.size() + "§a блоков. Схема привязана к вашим координатам. Начинаю строительство..."));

            // Запускаем строительство
            executor.startBuild(offsetBlocks);

        } catch (Exception e) {
            source.sendFeedback(Text.literal("§c[HB] Ошибка парсинга: " + e.getMessage()));
            HumanBuilderMod.LOGGER.error("Ошибка парсинга схемы", e);
            return 0;
        }

        return 1;
    }

    private static int executeStop(FabricClientCommandSource source) {
        if (!executor.isActive()) {
            source.sendFeedback(Text.literal("§e[HB] Строительство не запущено."));
            return 0;
        }

        executor.stopBuild();
        return 1;
    }

    private static int executeStatus(FabricClientCommandSource source) {
        if (!executor.isActive()) {
            source.sendFeedback(Text.literal("§e[HB] Строительство не запущено."));
            return 0;
        }

        int placed = executor.getBlocksPlaced();
        int total = executor.getTotalBlocks();
        int percent = total > 0 ? (placed * 100 / total) : 0;

        source.sendFeedback(Text.literal(
            "§a[HB] Статус: §e" + executor.getState().name()
            + "§r | Прогресс: §e" + percent + "%§r (" + placed + "/" + total + ")"
        ));

        return 1;
    }

    // ════════════════════════════════════════════════════════════════════
    //  Утилиты
    // ════════════════════════════════════════════════════════════════════

    /**
     * Находит файл схемы.
     *
     * Порядок поиска:
     * 1. Абсолютный путь (если начинается с / или C:\).
     * 2. Относительно .minecraft/schematics/
     * 3. Относительно .minecraft/config/litematica/
     */
    private static File resolveSchematicFile(String filename) {
        // Абсолютный путь
        File absolute = new File(filename);
        if (absolute.isAbsolute() && absolute.exists()) {
            return absolute;
        }

        // Добавляем расширение, если не указано
        if (!filename.endsWith(".litematic") && !filename.endsWith(".schematic") && !filename.endsWith(".schem")) {
            filename = filename + ".litematic";
        }

        // .minecraft/schematics/
        File schematicsDir = getSchematicsDir();
        File inSchematics = new File(schematicsDir, filename);
        if (inSchematics.exists()) {
            return inSchematics;
        }

        // .minecraft/config/litematica/
        File gameDir = MinecraftClient.getInstance().runDirectory;
        File litematicaDir = new File(gameDir, "config/litematica");
        File inLitematica = new File(litematicaDir, filename);
        if (inLitematica.exists()) {
            return inLitematica;
        }

        return inSchematics; // вернём путь для сообщения об ошибке
    }

    /**
     * Папка schematics в .minecraft (создаёт, если не существует).
     */
    private static File getSchematicsDir() {
        File gameDir = MinecraftClient.getInstance().runDirectory;
        File schematics = new File(gameDir, "schematics");
        if (!schematics.exists()) {
            schematics.mkdirs();
        }
        return schematics;
    }
}

package com.humanbuilder.command;

import com.humanbuilder.HumanBuilderMod;
import com.humanbuilder.executor.BuildExecutor;
import com.humanbuilder.executor.BuildState;
import com.humanbuilder.gui.HumanBuilderScreen;
import com.humanbuilder.parser.SchematicParser;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.io.File;
import java.util.Map;

/**
 * Команда чата для управления строительством.
 *
 * Использование:
 *   /humanbuilder view <файл.litematic>   — загрузить голограмму для настройки позиции
 *   /humanbuilder start                    — начать строительство из текущей позиции голограммы
 *   /humanbuilder build <файл.litematic>   — быстрый старт (загрузить + сразу строить)
 *   /humanbuilder stop                     — остановить строительство
 *   /humanbuilder pause                    — приостановить
 *   /humanbuilder resume                   — возобновить
 *   /humanbuilder move <dx> <dy> <dz>      — сдвинуть голограмму
 *   /humanbuilder status                   — показать прогресс
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
                    // /humanbuilder view <filename> — загрузить превью голограммы
                    .then(ClientCommandManager.literal("view")
                        .then(ClientCommandManager.argument("file", StringArgumentType.greedyString())
                            .executes(ctx -> executeView(
                                ctx.getSource(),
                                StringArgumentType.getString(ctx, "file")
                            ))
                        )
                    )
                    // /humanbuilder start — запустить строительство из текущей позиции голограммы
                    .then(ClientCommandManager.literal("start")
                        .executes(ctx -> executeStart(ctx.getSource()))
                    )
                    // /humanbuilder build <filename> — быстрый старт
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
                    // /humanbuilder pause
                    .then(ClientCommandManager.literal("pause")
                        .executes(ctx -> {
                            executor.pauseBuild();
                            return 1;
                        })
                    )
                    // /humanbuilder resume
                    .then(ClientCommandManager.literal("resume")
                        .executes(ctx -> {
                            executor.resumeBuild();
                            return 1;
                        })
                    )
                    .then(ClientCommandManager.literal("gui")
                        .executes(ctx -> {
                            MinecraftClient client = MinecraftClient.getInstance();
                            client.setScreen(new HumanBuilderScreen(executor));
                            return 1;
                        })
                    )
                    // /humanbuilder move <dx> <dy> <dz>
                    .then(ClientCommandManager.literal("move")
                        .then(ClientCommandManager.argument("dx", IntegerArgumentType.integer())
                            .then(ClientCommandManager.argument("dy", IntegerArgumentType.integer())
                                .then(ClientCommandManager.argument("dz", IntegerArgumentType.integer())
                                    .executes(ctx -> {
                                        int dx = IntegerArgumentType.getInteger(ctx, "dx");
                                        int dy = IntegerArgumentType.getInteger(ctx, "dy");
                                        int dz = IntegerArgumentType.getInteger(ctx, "dz");
                                        executor.moveSchematic(dx, dy, dz);
                                        return 1;
                                    })
                                )
                            )
                        )
                    )
                    // /humanbuilder mode [layered|architectural]
                    .then(ClientCommandManager.literal("mode")
                        .then(ClientCommandManager.literal("layered")
                            .executes(ctx -> {
                                executor.setSortMode(com.humanbuilder.logic.SortMode.LAYERED);
                                ctx.getSource().sendFeedback(Text.literal("§a[HB] Режим: §eпо слоям снизу вверх"));
                                return 1;
                            })
                        )
                        .then(ClientCommandManager.literal("architectural")
                            .executes(ctx -> {
                                executor.setSortMode(com.humanbuilder.logic.SortMode.ARCHITECTURAL);
                                ctx.getSource().sendFeedback(Text.literal(
                                        "§a[HB] Режим: §eконтур → каркас → заполнение → детали"));
                                return 1;
                            })
                        )
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

    /**
     * /humanbuilder view <file> — загрузить голограмму для настройки позиции.
     */
    private static int executeView(FabricClientCommandSource source, String filename) {
        if (executor.isActive()) {
            source.sendFeedback(Text.literal("§c[HB] Сначала остановите текущий процесс: /humanbuilder stop"));
            return 0;
        }

        MinecraftClient client = MinecraftClient.getInstance();

        // ── Поиск файла ──────────────────────────────────────────────
        File schematicFile = resolveSchematicFile(filename);
        if (schematicFile == null || !schematicFile.exists()) {
            source.sendFeedback(Text.literal("§c[HB] Файл не найден: " + filename));
            source.sendFeedback(Text.literal("§7    Путь: " + getSchematicsDir().getAbsolutePath()));
            return 0;
        }

        source.sendFeedback(Text.literal("§a[HB] Загрузка превью: §e" + schematicFile.getName() + "§a..."));

        try {
            SchematicParser parser = new SchematicParser();
            Map<BlockPos, BlockState> blocks = parser.parse(schematicFile);

            if (blocks.isEmpty()) {
                source.sendFeedback(Text.literal("§c[HB] Схема пуста или не содержит блоков."));
                return 0;
            }

            // Загружаем голограмму в режиме превью
            executor.loadSchematic(blocks);

        } catch (Exception e) {
            source.sendFeedback(Text.literal("§c[HB] Ошибка парсинга: " + e.getMessage()));
            HumanBuilderMod.LOGGER.error("Ошибка парсинга схемы", e);
            return 0;
        }

        return 1;
    }

    /**
     * /humanbuilder start — запустить строительство из текущей позиции голограммы.
     */
    private static int executeStart(FabricClientCommandSource source) {
        if (executor.getState() != BuildState.PREVIEW) {
            source.sendFeedback(Text.literal("§c[HB] Сначала загрузите голограмму: /humanbuilder view <файл>"));
            return 0;
        }

        // ── Проверка Creative Mode ───────────────────────────────────
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null && !client.player.getAbilities().creativeMode) {
            source.sendFeedback(Text.literal("§c[HB] Ошибка: HumanBuilder работает только в Creative Mode!"));
            return 0;
        }

        executor.startBuild();
        return 1;
    }

    /**
     * /humanbuilder build <file> — быстрый старт (загрузка + сразу строительство).
     */
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

        try {
            SchematicParser parser = new SchematicParser();
            Map<BlockPos, BlockState> blocks = parser.parse(schematicFile);

            if (blocks.isEmpty()) {
                source.sendFeedback(Text.literal("§c[HB] Схема пуста или не содержит блоков."));
                return 0;
            }

            source.sendFeedback(Text.literal("§a[HB] Загружено §e" + blocks.size() + "§a блоков. Начинаю строительство..."));

            // Запускаем строительство (быстрый старт)
            executor.startBuild(blocks);

        } catch (Exception e) {
            source.sendFeedback(Text.literal("§c[HB] Ошибка парсинга: " + e.getMessage()));
            HumanBuilderMod.LOGGER.error("Ошибка парсинга схемы", e);
            return 0;
        }

        return 1;
    }

    private static int executeStop(FabricClientCommandSource source) {
        boolean stopped = false;
        if (executor.isActive()) {
            executor.stopBuild();
            stopped = true;
        }
        if (!stopped) {
            source.sendFeedback(Text.literal("§e[HB] Строительство не запущено."));
            return 0;
        }
        return 1;
    }

    private static int executeStatus(FabricClientCommandSource source) {
        BuildState currentState = executor.getState();

        if (currentState == BuildState.IDLE) {
            source.sendFeedback(Text.literal("§e[HB] Строительство не запущено."));
            return 0;
        }

        if (currentState == BuildState.PREVIEW) {
            source.sendFeedback(Text.literal(
                "§a[HB] Статус: §eПРЕВЬЮ"
                + "§r | Блоков в схеме: §e" + executor.getTotalBlocks()
                + "§r | Палка: §e" + (executor.isStickMoveMode() ? "АКТИВНА" : "зафиксирована")
            ));
            return 1;
        }

        int placed = executor.getBlocksPlaced();
        int total = executor.getTotalBlocks();
        int percent = total > 0 ? (placed * 100 / total) : 0;

        source.sendFeedback(Text.literal(
            "§a[HB] Статус: §e" + currentState.name()
            + "§r | Прогресс: §e" + percent + "%§r (" + placed + "/" + total + ")"
            + "§r | Режим: §e" + executor.getSortMode().name()
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
    public static File resolveSchematicFile(String filename) {
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
    public static File getSchematicsDir() {
        File gameDir = MinecraftClient.getInstance().runDirectory;
        File schematics = new File(gameDir, "schematics");
        if (!schematics.exists()) {
            schematics.mkdirs();
        }
        return schematics;
    }
}

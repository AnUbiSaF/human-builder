package com.humanbuilder.executor;

import com.humanbuilder.HumanBuilderMod;
import com.humanbuilder.camera.CameraSmoother;
import com.humanbuilder.logic.BlockCategory;
import com.humanbuilder.logic.BuildEntry;
import com.humanbuilder.logic.BuildLogicSorter;
import com.humanbuilder.logic.SortMode;
import com.humanbuilder.logic.TemporarySupportPathfinder;
import com.humanbuilder.movement.MovementController;
import com.humanbuilder.placer.BlockPlacer;
import com.humanbuilder.timing.HumanTiming;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.*;

/**
 * Главная стейт-машина, оркестрирующая весь процесс строительства.
 *
 * Цикл работы:
 *   IDLE → SORTING → WALKING → LOOKING → PLACING → WAITING → WALKING → ...
 *
 * Поддерживает два режима:
 *   - PREVIEW (предпросмотр голограммы, позиционирование палкой)
 *   - Активная постройка (SORTING, WALKING, LOOKING, PLACING, WAITING)
 *
 * Каждый тик вызывается tick(), который обрабатывает текущее состояние
 * и при необходимости переходит к следующему.
 */
public class BuildExecutor {

    private static final int MAX_BREAK_CHAIN = 6;
    private static final int MAX_BREAK_NAVIGATION_FAILURES = 3;
    private static final long BASE_NAVIGATION_COOLDOWN_TICKS = 40L;
    private static final int WALKING_WATCHDOG_TICKS = 600;
    private static final int MAX_TEMPORARY_SUPPORT_LENGTH = 96;
    private static final int MAX_TEMPORARY_SUPPORT_SEARCH_NODES = 16_384;
    private static final int MAX_SUPPORT_WORLD_RECHECKS = 4;

    private final MinecraftClient client;
    private final CameraSmoother camera;
    private final MovementController movement;
    private final BlockPlacer placer;
    private final HumanTiming timing;
    private final BuildLogicSorter sorter;

    // ── Состояние ──────────────────────────────────────────────────────
    private BuildState state = BuildState.IDLE;

    /** Очередь блоков для установки */
    private Deque<BuildEntry> buildQueue = new ArrayDeque<>();
    private final Deque<BlockPos> clearingQueue = new ArrayDeque<>();
    private final Deque<BuildEntry> temporarySupportQueue = new ArrayDeque<>();
    private final LinkedHashMap<BlockPos, BlockState> temporarySupports = new LinkedHashMap<>();
    private final Deque<BlockPos> temporarySupportCleanupQueue = new ArrayDeque<>();
    private final NavigableMap<Integer, Deque<BuildEntry>> layerQueues = new TreeMap<>();

    /**
     * Исходная карта блоков схемы в ОТНОСИТЕЛЬНЫХ координатах (от 0,0,0).
     * Не меняется при перемещении голограммы.
     */
    private Map<BlockPos, BlockState> relativeBlocks = new HashMap<>();
    private Map<BlockPos, BlockState> absoluteBlocksCache = Collections.emptyMap();
    private BlockPos absoluteBlocksCacheOrigin;
    private List<BuildEntry> previewRelativeEntries = Collections.emptyList();
    private final Map<Long, List<BuildEntry>> previewEntriesByChunk = new HashMap<>();

    /**
     * Точка привязки голограммы/схемы в мировых координатах.
     * Абсолютные координаты блока = relativePos + schematicOrigin.
     */
    private BlockPos schematicOrigin = BlockPos.ORIGIN;

    /** Состояние, которое было до постановки на паузу */
    private BuildState savedStateBeforePause = BuildState.SORTING;

    /** Текущий блок, который мы собираемся поставить */
    private BuildEntry currentEntry;

    /** Категория предыдущего блока (для определения задержки при смене категории) */
    private BlockCategory lastCategory;

    /** Счётчик тиков ожидания */
    private int waitTicks = 0;

    /** Сколько тиков ещё ждать */
    private int waitTarget = 0;

    /** Нужно ли после ожидания вернуться к текущему блоку, а не брать следующий */
    private boolean resumeLookingAfterWait = false;

    /** Number of inventory synchronization attempts for the current block. */
    private int hotbarSyncAttempts = 0;

    /** Нужно ли после ожидания пойти к блоку (для пауз на смену категорий/этажей) */
    private boolean resumeWalkingAfterWait = false;

    /** Статистика */
    private int blocksPlaced = 0;
    private int totalBlocks = 0;
    private BlockPos diagnosticTarget;
    private BuildState diagnosticState;
    private int diagnosticTargetTicks;

    /** Режим перемещения палкой: голограмма следует за прицелом */
    private boolean stickMoveMode = false;
    private boolean hologramVisible = true;

    /** Режим сортировки блоков схемы */
    private SortMode sortMode = SortMode.ARCHITECTURAL;

    /** Настройка скорости кинематографического режима (timelapse) */
    private boolean cinematicFast = true;

    /** Последний установленный блок (для группировки по материалу) */
    private Block lastPlacedBlock;

    /** Флаг, указывающий, что последняя попытка поставить блок провалилась */
    private boolean placementFailed = false;

    /** Счетчик подряд идущих неудачных попыток установки для одного блока */
    private int placementFailureCount = 0;
    private BlockPos lastFailedPos = null;
    private final Map<BlockPos, Integer> navigationFailures = new HashMap<>();
    private final Map<BlockPos, Long> navigationRetryAfter = new HashMap<>();
    private final Map<BlockPos, Integer> supportWorldRechecks = new HashMap<>();
    private final Set<BlockPos> activeBatch = new HashSet<>();
    private final Map<BlockPos, BuildEntry> pendingEntries = new HashMap<>();
    private final Map<Long, Map<BlockPos, BuildEntry>> pendingEntriesByChunk = new HashMap<>();
    private final Map<BlockPos, PlacementVerification> placementVerifications = new HashMap<>();
    private long executorTicks;
    private BlockPos blockToBreak;
    private final Deque<BlockPos> breakTargetStack = new ArrayDeque<>();
    private final Set<BlockPos> visitedBreakTargets = new HashSet<>();
    private int breakTicks;
    private int breakAttempts;
    private int breakNavigationFailures;
    private int breakConfirmationTicks;
    private Integer temporarySupportOwnerGroup;
    private long temporarySupportCleanupRetryAt;
    private final Map<String, Long> lastWarningTicks = new HashMap<>();
    private boolean placementSentThisTick;
    private long stateEnteredAtTick;

    public SortMode getSortMode() {
        return sortMode;
    }

    public void setSortMode(SortMode sortMode) {
        this.sortMode = sortMode;
        boolean cinematicRoute = sortMode.isCinematic();
        boolean requireNaturalLineOfSight = cinematicRoute && !cinematicFast;
        this.movement.setCinematicMode(cinematicRoute);
        this.movement.setCinematicFast(this.cinematicFast);
        this.placer.setLineOfSightRequired(requireNaturalLineOfSight);
        // Если строительство активно, пересчитываем очередь с новым режимом
        if (state != BuildState.IDLE && state != BuildState.PREVIEW) {
            rebuildQueue();
        }
    }

    public boolean isCinematicFast() {
        return cinematicFast;
    }

    public int getArchitecturalLayeredBaseHeight() {
        return sorter.getArchitecturalLayeredBaseHeight();
    }

    public void setArchitecturalLayeredBaseHeight(int height) {
        if (state != BuildState.IDLE && state != BuildState.PREVIEW) return;
        sorter.setArchitecturalLayeredBaseHeight(height);
    }

    public boolean isClearingObstacles() {
        return sortMode == SortMode.ARCHITECTURAL && !clearingQueue.isEmpty();
    }

    public int getObstaclesRemaining() {
        return clearingQueue.size();
    }

    public int getTemporarySupportsRemaining() {
        return temporarySupports.size() + temporarySupportQueue.size();
    }

    public void setCinematicFast(boolean cinematicFast) {
        this.cinematicFast = cinematicFast;
        this.movement.setCinematicFast(cinematicFast);
        boolean cinematicRoute = sortMode.isCinematic();
        this.movement.setCinematicMode(cinematicRoute);
        this.placer.setLineOfSightRequired(cinematicRoute && !cinematicFast);
    }

    public BuildExecutor(
            MinecraftClient client,
            CameraSmoother camera,
            MovementController movement,
            BlockPlacer placer,
            HumanTiming timing
    ) {
        this.client = client;
        this.camera = camera;
        this.movement = movement;
        this.placer = placer;
        this.placer.setExecutor(this);
        this.timing = timing;
        this.sorter = new BuildLogicSorter();
        this.movement.setReservedBuildPositionPredicate(pos -> {
            if (currentEntry != null && pos.equals(currentEntry.pos())) return true;
            if (activeBatch.contains(pos)) return true;
            if (blockToBreak != null && pos.equals(blockToBreak)) return true;
            if (placementVerifications.containsKey(pos)) return true;
            return false;
        });
    }

    // ════════════════════════════════════════════════════════════════════
    //  Публичный API
    // ════════════════════════════════════════════════════════════════════

    /**
     * Загрузить схему и показать голограмму (режим превью).
     * Блоки хранятся в относительных координатах от (0,0,0).
     * Точка привязки устанавливается перед игроком.
     *
     * @param blocks карта блоков из парсера (позиции могут быть любыми, будут нормализованы)
     */
    public void loadSchematic(Map<BlockPos, BlockState> blocks) {
        if (client.player == null) {
            sendMessage("§c[HB] Ошибка: игрок не найден");
            return;
        }

        if (blocks.isEmpty()) {
            sendMessage("§c[HB] Схема пуста!");
            return;
        }

        // Нормализуем позиции: находим минимальные координаты и сдвигаем всё к (0,0,0)
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        for (BlockPos pos : blocks.keySet()) {
            if (pos.getX() < minX) minX = pos.getX();
            if (pos.getY() < minY) minY = pos.getY();
            if (pos.getZ() < minZ) minZ = pos.getZ();
        }

        relativeBlocks = new HashMap<>();
        for (var entry : blocks.entrySet()) {
            BlockPos relPos = new BlockPos(
                entry.getKey().getX() - minX,
                entry.getKey().getY() - minY,
                entry.getKey().getZ() - minZ
            );
            relativeBlocks.put(relPos, entry.getValue());
        }
        previewRelativeEntries = sorter.categorize(relativeBlocks);
        rebuildPreviewIndex();

        // Ставим точку привязки перед игроком (3 блока вперёд)
        net.minecraft.util.math.Direction facing = client.player.getHorizontalFacing();
        schematicOrigin = client.player.getBlockPos().offset(facing, 3);
        invalidateAbsoluteBlocksCache();

        // Переходим в режим превью
        initializeBuildQueues(List.of());
        totalBlocks = relativeBlocks.size();
        blocksPlaced = 0;
        lastCategory = null;
        currentEntry = null;
        clearBreakingState();
        resumeLookingAfterWait = false;
        resumeWalkingAfterWait = false;
        stickMoveMode = true;

        setState(BuildState.PREVIEW);
        sendMessage("§a[HB] Голограмма загружена! §e" + totalBlocks + "§a блоков.");
        sendMessage("§7[HB] Возьмите §eпалку §7и наведите прицел, чтобы переместить. §eПКМ §7— зафиксировать.");
        sendMessage("§7[HB] После настройки введите: §a/humanbuilder start");
    }

    /**
     * Начать строительство из текущей позиции голограммы.
     * Вычисляет абсолютные координаты блоков и запускает стейт-машину.
     */
    public void startBuild() {
        if (client.player == null) {
            sendMessage("§c[HB] Ошибка: игрок не найден");
            return;
        }

        if (relativeBlocks.isEmpty()) {
            sendMessage("§c[HB] Схема не загружена! Используйте /humanbuilder view <файл>");
            return;
        }

        // Вычисляем абсолютные координаты из относительных + origin
        Map<BlockPos, BlockState> absoluteBlocks = getAbsoluteBlocks();

        // Сортируем блоки
        List<BuildEntry> sorted = sorter.sort(absoluteBlocks, client.player.getBlockPos(), sortMode);
        initializeBuildQueues(sorted);
        totalBlocks = sorted.size();
        blocksPlaced = 0;
        lastCategory = null;
        currentEntry = null;
        clearBreakingState();
        resumeLookingAfterWait = false;
        resumeWalkingAfterWait = false;
        stickMoveMode = false;
        placementFailed = false;
        placementFailureCount = 0;
        lastFailedPos = null;
        navigationFailures.clear();
        navigationRetryAfter.clear();
        supportWorldRechecks.clear();
        lastWarningTicks.clear();
        activeBatch.clear();
        hotbarSyncAttempts = 0;
        savedStateBeforePause = BuildState.SORTING;

        setState(BuildState.SORTING);
        sendMessage("§a[HB] Строительство начато! Блоков: " + totalBlocks);
    }

    /**
     * Начать строительство из карты блоков (быстрый старт через /humanbuilder build).
     * Совместимость со старым API.
     */
    public void startBuild(Map<BlockPos, BlockState> blocks) {
        if (client.player == null) {
            sendMessage("§c[HB] Ошибка: игрок не найден");
            return;
        }

        if (blocks.isEmpty()) {
            sendMessage("§c[HB] Схема пуста!");
            return;
        }

        // Нормализуем и устанавливаем origin как в loadSchematic
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        for (BlockPos pos : blocks.keySet()) {
            if (pos.getX() < minX) minX = pos.getX();
            if (pos.getY() < minY) minY = pos.getY();
            if (pos.getZ() < minZ) minZ = pos.getZ();
        }

        relativeBlocks = new HashMap<>();
        for (var entry : blocks.entrySet()) {
            BlockPos relPos = new BlockPos(
                entry.getKey().getX() - minX,
                entry.getKey().getY() - minY,
                entry.getKey().getZ() - minZ
            );
            relativeBlocks.put(relPos, entry.getValue());
        }
        previewRelativeEntries = sorter.categorize(relativeBlocks);
        rebuildPreviewIndex();

        // Origin: перед игроком
        net.minecraft.util.math.Direction facing = client.player.getHorizontalFacing();
        schematicOrigin = client.player.getBlockPos().offset(facing, 3);
        invalidateAbsoluteBlocksCache();

        stickMoveMode = false;

        // Сразу запускаем стройку
        Map<BlockPos, BlockState> absoluteBlocks = getAbsoluteBlocks();
        List<BuildEntry> sorted = sorter.sort(absoluteBlocks, client.player.getBlockPos(), sortMode);
        initializeBuildQueues(sorted);
        totalBlocks = sorted.size();
        blocksPlaced = 0;
        lastCategory = null;
        currentEntry = null;
        clearBreakingState();
        resumeLookingAfterWait = false;
        resumeWalkingAfterWait = false;
        placementFailed = false;
        placementFailureCount = 0;
        lastFailedPos = null;
        navigationFailures.clear();
        navigationRetryAfter.clear();
        supportWorldRechecks.clear();
        lastWarningTicks.clear();
        activeBatch.clear();
        hotbarSyncAttempts = 0;
        savedStateBeforePause = BuildState.SORTING;

        setState(BuildState.SORTING);
        sendMessage("§a[HB] Строительство начато! Блоков: " + totalBlocks);
    }

    /**
     * Остановить строительство.
     */
    public void stopBuild() {
        setState(BuildState.IDLE);
        camera.stop();
        movement.reset();
        initializeBuildQueues(List.of());
        currentEntry = null;
        clearBreakingState();
        relativeBlocks.clear();
        previewRelativeEntries = Collections.emptyList();
        previewEntriesByChunk.clear();
        schematicOrigin = BlockPos.ORIGIN;
        invalidateAbsoluteBlocksCache();
        stickMoveMode = false;
        resumeLookingAfterWait = false;
        resumeWalkingAfterWait = false;
        placementFailed = false;
        placementFailureCount = 0;
        lastFailedPos = null;
        navigationFailures.clear();
        navigationRetryAfter.clear();
        supportWorldRechecks.clear();
        lastWarningTicks.clear();
        activeBatch.clear();
        hotbarSyncAttempts = 0;
        sendMessage("§e[HB] Строительство остановлено. Поставлено: " + blocksPlaced + "/" + totalBlocks);
    }

    /**
     * Поставить строительство на паузу.
     */
    public void pauseBuild() {
        if (state == BuildState.IDLE || state == BuildState.PAUSED || state == BuildState.PREVIEW) {
            sendMessage("§c[HB] Нельзя приостановить: строительство не активно или уже на паузе.");
            return;
        }
        if (state == BuildState.WAITING) {
            savedStateBeforePause = resumeLookingAfterWait && currentEntry != null
                    ? BuildState.LOOKING
                    : resumeWalkingAfterWait && currentEntry != null
                            ? BuildState.WALKING
                            : BuildState.SORTING;
        } else {
            savedStateBeforePause = state;
        }
        resumeLookingAfterWait = false;
        resumeWalkingAfterWait = false;
        setState(BuildState.PAUSED);
        movement.stop();
        camera.stop();
        sendMessage("§e[HB] Строительство приостановлено! Для возобновления введите: §a/humanbuilder resume");
    }

    /**
     * Возобновить строительство с паузы.
     */
    public void resumeBuild() {
        if (state != BuildState.PAUSED) {
            sendMessage("§c[HB] Строительство не находится на паузе.");
            return;
        }
        sendMessage("§a[HB] Возобновление строительства...");
        setState(savedStateBeforePause != null ? savedStateBeforePause : BuildState.SORTING);
    }

    /**
     * Переместить голограмму (и оставшуюся очередь) на вектор (dx, dy, dz).
     */
    public void moveSchematic(int dx, int dy, int dz) {
        if (state == BuildState.IDLE) {
            sendMessage("§c[HB] Схема не загружена. Сначала запустите команду /humanbuilder view");
            return;
        }

        schematicOrigin = schematicOrigin.add(dx, dy, dz);
        invalidateAbsoluteBlocksCache();

        // Если строительство идёт — пересортировываем очередь
        if (state != BuildState.PREVIEW) {
            rebuildQueue();
        }

        sendMessage("§a[HB] Голограмма смещена на (" + dx + ", " + dy + ", " + dz + ").");
    }

    /**
     * Устанавливает новую точку привязки голограммы.
     */
    public void setSchematicOrigin(BlockPos newOrigin) {
        this.schematicOrigin = newOrigin;
        invalidateAbsoluteBlocksCache();

        // Если строительство уже идёт (не превью) — пересортировываем
        if (state != BuildState.PREVIEW && state != BuildState.IDLE) {
            rebuildQueue();
        }
    }

    /**
     * Зафиксировать голограмму (вызывается при ПКМ палкой).
     */
    public void lockHologram() {
        stickMoveMode = false;
        sendMessage("§a[HB] ✓ Позиция голограммы зафиксирована!");
        sendMessage("§7[HB] Для запуска строительства введите: §a/humanbuilder start");
    }

    /**
     * Главный тик — вызывается из TickHandler каждый клиентский тик.
     */
    public void tick() {
        if (state == BuildState.IDLE || state == BuildState.PAUSED || state == BuildState.PREVIEW) return;
        if (client.player == null) return;

        executorTicks++;
        placementSentThisTick = false;
        processPlacementVerifications();
        updateDiagnostics();

        // Collapse decision-only transitions into this tick. Interaction and
        // movement states still yield, so at most one block packet is sent per
        // client tick while nearby blocks no longer cost 4-5 empty ticks each.
        for (int transitions = 0; transitions < 8; transitions++) {
            BuildState handledState = state;
            switch (handledState) {
                case SORTING  -> tickSorting();
                case WALKING  -> tickWalking();
                case BREAKING -> tickBreaking();
                case CLEANING_SUPPORTS -> tickTemporarySupportCleanup();
                case LOOKING  -> tickLooking();
                case PLACING  -> tickPlacing();
                case WAITING  -> tickWaiting();
                case MISTAKE  -> tickMistake();
                default       -> { return; }
            }

            if (placementSentThisTick
                    || handledState == BuildState.PLACING
                    || handledState == BuildState.BREAKING
                    || handledState == BuildState.MISTAKE
                    || state == BuildState.IDLE
                    || state == BuildState.PAUSED
                    || state == BuildState.PREVIEW
                    || state == BuildState.WAITING
                    || state == BuildState.BREAKING
                    || state == handledState) {
                return;
            }
        }
    }

    /**
     * Тик для режима превью: если палка в руке — двигаем голограмму за прицелом.
     */
    public void tickPreview() {
        if (state != BuildState.PREVIEW || !stickMoveMode) return;
        if (client.player == null || client.world == null) return;

        // Проверяем, что в руке палка (stick)
        var mainHand = client.player.getMainHandStack();
        if (mainHand.isEmpty() || !net.minecraft.registry.Registries.ITEM.getId(mainHand.getItem()).getPath().equals("stick")) {
            return;
        }

        // Raycast: куда смотрит игрок (до 64 блоков)
        net.minecraft.util.hit.HitResult hitResult = client.player.raycast(64.0, 1.0f, false);
        if (hitResult.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK) {
            net.minecraft.util.hit.BlockHitResult blockHit = (net.minecraft.util.hit.BlockHitResult) hitResult;
            // Ставим origin на верхнюю грань нажатого блока
            BlockPos hitPos = blockHit.getBlockPos();
            net.minecraft.util.math.Direction hitSide = blockHit.getSide();
            BlockPos newOrigin = hitPos.offset(hitSide);
            if (!schematicOrigin.equals(newOrigin)) {
                schematicOrigin = newOrigin;
                invalidateAbsoluteBlocksCache();
            }
        }
    }

    // ── Геттеры ──────────────────────────────────────────────────────

    public BuildState getState() { return state; }
    public BuildEntry getCurrentEntry() { return currentEntry; }
    public int getBlocksPlaced() { return blocksPlaced; }
    public int getTotalBlocks()  { return totalBlocks; }
    public boolean isActive()    { return state != BuildState.IDLE; }
    public BlockPos getSchematicOrigin() { return schematicOrigin; }
    public boolean isStickMoveMode() { return stickMoveMode; }
    public boolean isPreview()   { return state == BuildState.PREVIEW; }
    public boolean isHologramVisible() { return hologramVisible; }
    public void setHologramVisible(boolean visible) { hologramVisible = visible; }

    /**
     * Возвращает абсолютные координаты всех блоков схемы (relative + origin).
     */
    public Map<BlockPos, BlockState> getAbsoluteBlocks() {
        if (schematicOrigin.equals(absoluteBlocksCacheOrigin)) return absoluteBlocksCache;

        Map<BlockPos, BlockState> abs = new HashMap<>();
        for (var entry : relativeBlocks.entrySet()) {
            abs.put(entry.getKey().add(schematicOrigin), entry.getValue());
        }
        absoluteBlocksCache = Collections.unmodifiableMap(abs);
        absoluteBlocksCacheOrigin = schematicOrigin.toImmutable();
        return absoluteBlocksCache;
    }

    /**
     * Возвращает список всех оставшихся блоков схемы в очереди.
     * Используется рендерером голограммы.
     */
    public List<BuildEntry> getRemainingEntries() {
        // В режиме превью возвращаем все блоки схемы (абсолютные координаты)
        if (state == BuildState.PREVIEW) {
            List<BuildEntry> list = new ArrayList<>(previewRelativeEntries.size());
            for (BuildEntry entry : previewRelativeEntries) {
                list.add(new BuildEntry(
                        entry.pos().add(schematicOrigin), entry.state(),
                        entry.category(), entry.workGroup()));
            }
            return list;
        }

        if (state == BuildState.IDLE) return List.of();
        return new ArrayList<>(pendingEntries.values());
    }

    /**
     * Collects only nearby entries for the hologram. In preview mode this
     * avoids translating and allocating the complete schematic every frame.
     */
    public List<BuildEntry> getVisibleEntries(BlockPos center, int radius, int limit) {
        if (limit <= 0 || state == BuildState.IDLE) return List.of();

        int radiusSq = radius * radius;
        Comparator<BuildEntry> farthestFirst = Comparator
                .comparingDouble((BuildEntry entry) -> entry.pos().getSquaredDistance(center))
                .reversed();
        PriorityQueue<BuildEntry> closest = new PriorityQueue<>(limit, farthestFirst);

        if (state == BuildState.PREVIEW) {
            int relativeCenterX = center.getX() - schematicOrigin.getX();
            int relativeCenterZ = center.getZ() - schematicOrigin.getZ();
            int minChunkX = (relativeCenterX - radius) >> 4;
            int maxChunkX = (relativeCenterX + radius) >> 4;
            int minChunkZ = (relativeCenterZ - radius) >> 4;
            int maxChunkZ = (relativeCenterZ + radius) >> 4;
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    for (BuildEntry relative : previewEntriesByChunk.getOrDefault(
                            chunkKey(chunkX, chunkZ), List.of())) {
                        int x = relative.pos().getX() + schematicOrigin.getX();
                        int y = relative.pos().getY() + schematicOrigin.getY();
                        int z = relative.pos().getZ() + schematicOrigin.getZ();
                        double dx = x - center.getX();
                        double dy = y - center.getY();
                        double dz = z - center.getZ();
                        if (dx * dx + dy * dy + dz * dz > radiusSq) continue;

                        offerVisibleEntry(closest,
                                new BuildEntry(
                                        new BlockPos(x, y, z), relative.state(),
                                        relative.category(), relative.workGroup()),
                                center, limit);
                    }
                }
            }
        } else {
            int minChunkX = (center.getX() - radius) >> 4;
            int maxChunkX = (center.getX() + radius) >> 4;
            int minChunkZ = (center.getZ() - radius) >> 4;
            int maxChunkZ = (center.getZ() + radius) >> 4;
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    Map<BlockPos, BuildEntry> chunkEntries = pendingEntriesByChunk.get(
                            chunkKey(chunkX, chunkZ));
                    if (chunkEntries == null) continue;
                    for (BuildEntry entry : chunkEntries.values()) {
                        if (entry.pos().getSquaredDistance(center) <= radiusSq) {
                            offerVisibleEntry(closest, entry, center, limit);
                        }
                    }
                }
            }
        }

        List<BuildEntry> result = new ArrayList<>(closest);
        result.sort(Comparator.comparingDouble(entry -> entry.pos().getSquaredDistance(center)));
        return List.copyOf(result);
    }

    private void offerVisibleEntry(PriorityQueue<BuildEntry> closest, BuildEntry candidate,
                                   BlockPos center, int limit) {
        if (closest.size() < limit) {
            closest.offer(candidate);
            return;
        }

        BuildEntry farthest = closest.peek();
        if (farthest != null && candidate.pos().getSquaredDistance(center)
                < farthest.pos().getSquaredDistance(center)) {
            closest.poll();
            closest.offer(candidate);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  Обработчики состояний
    // ════════════════════════════════════════════════════════════════════

    /**
     * SORTING: берём следующий блок из очереди и идём к нему.
     */
    private void tickSorting() {
        if (!advanceToNextBlock()) {
            if (state == BuildState.WAITING) return;
            if (state == BuildState.PAUSED) return;
            if (!placementVerifications.isEmpty()) {
                // Placement packets are optimistic on the client. Keep the
                // executor alive until the server had time to correct them.
                waitThenSort(1);
                return;
            }
            // Очередь пуста — строительство завершено
            sendMessage("§a[HB] ✓ Постройка завершена! Блоков: " + blocksPlaced);
            movement.reset();
            camera.stop();
            setState(BuildState.IDLE);
            return;
        }

        if (state == BuildState.BREAKING || state == BuildState.CLEANING_SUPPORTS) return;

        // Определяем задержку при смене категории
        if (lastCategory != null && currentEntry.category() != lastCategory) {
            if (buildStage(currentEntry.category()) > buildStage(lastCategory)) {
                // Переход между крупными этапами: каркас → заполнение → декор.
                sendMessage("§7[HB] " + currentEntry.category().getDisplayName() + "...");
                waitThenWalk(timing.floorTransitionDelay());
                return;
            }
        }

        setState(BuildState.WALKING);
    }

    private int buildStage(BlockCategory category) {
        return switch (category) {
            case FOUNDATION, PILLAR, WALL, ROOF -> 0;
            case WINDOW -> 1;
            case INTERIOR_WALL -> 2;
            case DECOR -> 3;
        };
    }

    /**
     * WALKING: идём к позиции, откуда можно поставить блок.
     */
    private void tickWalking() {
        if (currentEntry == null) {
            setState(BuildState.SORTING);
            return;
        }

        if (handleOccupiedCurrentTarget()) return;

        if (!movement.isActive() && movement.hasRouteFailure()) {
            handleNavigationFailure(movement.consumeFailureReason());
            return;
        }

        // A block may be placed while the player is still flying. Retarget the
        // active cinematic route immediately instead of finishing the obsolete
        // route and only then starting an approach to the new build cell.
        if (sortMode.isCinematic()
                && movement.isActive()
                && !Objects.equals(movement.getTargetPos(), currentEntry.pos())) {
            beginWalking(false);
            if (state != BuildState.WALKING) return;
        }

        // В кинематографическом режиме проверяем установку прямо на лету
        if (sortMode.isCinematic()) {
            boolean playerClear = !placer.wouldPlacementIntersectPlayer(
                    currentEntry.pos(), currentEntry.state());
            if (playerClear
                    && placer.canPlaceFromCurrentPosition(
                            currentEntry.pos(), currentEntry.state())) {
                if (!placer.isReady(currentEntry.state())) {
                    if (!placer.switchToBlock(currentEntry.state())) {
                        sendMessage("§c[HB] ⚠ Нет блока " + currentEntry.state().getBlock().getName().getString() + " в хотбаре или инвентаре!");
                        pauseBuild();
                        return;
                    }
                    if (!cinematicFast) {
                        waitThenWalk(timing.randomRange(4, 7));
                        return;
                    }
                }
                Vec3d lookTarget = placer.getPlacementLookTarget(currentEntry.pos(), currentEntry.state());
                if (placer.requiresPlacementFacing(currentEntry.state())) {
                    camera.lookAtWithYaw(lookTarget, placer.getPlacementYaw(currentEntry.state()));
                } else {
                    camera.lookAt(lookTarget);
                }
                float lookTolerance = !cinematicFast ? 4.5f : 45.0f;
                if (placer.isReady(currentEntry.state()) && isCameraLookingAtTarget(lookTolerance)) {
                    if (placer.placeBlock(currentEntry.pos(), currentEntry.state())) {
                        placementSentThisTick = true;
                        boolean requiresVerification = placer.requiresPlacementVerification(
                                currentEntry.state());
                        schedulePlacementVerification(currentEntry);
                        if (requiresVerification) {
                            addFirstQueuedEntry(currentEntry);
                        } else {
                            recordSuccessfulPlacement(currentEntry);
                        }
                        lastCategory = currentEntry.category();
                        lastPlacedBlock = currentEntry.state().getBlock();
                        placementFailed = false;
                        placementFailureCount = 0;
                        lastFailedPos = null;
                        navigationFailures.remove(currentEntry.pos());
                        currentEntry = null;
                        if (requiresVerification) {
                            waitThenSort(2);
                        } else if (!cinematicFast) {
                            waitThenSort(timing.randomRange(2, 4));
                        } else {
                            setState(BuildState.SORTING);
                        }
                        return;
                    }
                }
            } else {
                if (camera.isActive()) {
                    camera.stop();
                }
            }
        }

        // Movement verified the exact support-face hit when it completed the
        // route. Preserve that decision across the client-tick boundary.
        if (!movement.isActive() && movement.hasArrived()) {
            boolean reachedCurrentTarget = Objects.equals(
                    currentEntry.pos(), movement.getTargetPos());
            movement.consumeArrival();
            if (reachedCurrentTarget) {
                placementFailed = false;
                setState(BuildState.LOOKING);
                return;
            }
        }

        if (moveOutOfCurrentTargetIfNeeded()) return;

        // Если ходьба была инициирована из-за неуспешной установки блока
        if (placementFailed) {
            if (placer.canPlaceFromCurrentPosition(currentEntry.pos(), currentEntry.state())) {
                placementFailed = false;
                if (!sortMode.isCinematic()) {
                    movement.stop();
                    setState(BuildState.LOOKING);
                }
            } else if (!movement.isActive()) {
                beginWalking(true);
            }
            return;
        }

        // The real hit face already includes reach and line-of-sight checks.
        // A second center-distance check disagreed near roof edges and caused
        // completed routes to restart forever.
        if (placer.canPlaceFromCurrentPosition(currentEntry.pos(), currentEntry.state())) {
            if (!sortMode.isCinematic()) {
                movement.stop();
                setState(BuildState.LOOKING);
            }
            return;
        }

        // Если движение ещё не начато — запускаем
        if (!movement.isActive()) {
            if (sortMode.isCinematic() && !placer.isReady(currentEntry.state())) {
                if (!placer.switchToBlock(currentEntry.state())) {
                    sendMessage("§c[HB] ⚠ Нет блока " + currentEntry.state().getBlock().getName().getString() + " в хотбаре или инвентаре!");
                    pauseBuild();
                    return;
                }
                if (!cinematicFast) {
                    waitThenWalk(timing.randomRange(4, 7));
                    return;
                }
            }
            beginWalking(false);
        }
    }

    private void tickLooking() {
        if (currentEntry == null) {
            setState(BuildState.SORTING);
            return;
        }

        if (moveOutOfCurrentTargetIfNeeded()) return;

        // ── 1. Проверяем готовность блока в руке ──────────────────────
        // Если нужный блок еще не в руке (например, только берем из креатива),
        // запрашиваем его и ждем синхронизации с сервером
        if (!placer.isReady(currentEntry.state())) {
            if (hotbarSyncAttempts >= 3) {
                sendMessage("§c[HB] ⚠ Не удалось выбрать блок " + currentEntry.state().getBlock().getName().getString() + " в руку!");
                pauseBuild();
                return;
            }

            // Пытаемся взять блок в хотбар. Если не можем (в выживании нет блока) — останавливаемся
            if (!placer.switchToBlock(currentEntry.state())) {
                sendMessage("§c[HB] ⚠ Нет блока " + currentEntry.state().getBlock().getName().getString() + " в хотбаре или инвентаре!");
                pauseBuild();
                return;
            }

            hotbarSyncAttempts++;
            waitThenLook((sortMode.isCinematic() && !cinematicFast)
                    ? timing.randomRange(4, 7)
                    : 1);
            return;
        }

        hotbarSyncAttempts = 0;

        // Накапливаем тики наведения для обнаружения зависания LERP
        waitTicks++;

        // Получаем точку, куда нужно смотреть (центр грани опорного блока)
        Vec3d lookTarget = placer.getPlacementLookTarget(currentEntry.pos(), currentEntry.state());

        // Обновляем цель взгляда камеры каждый тик, компенсируя движение игрока
        boolean requiresFacing = placer.requiresPlacementFacing(currentEntry.state());
        if (requiresFacing) {
            camera.lookAtWithYaw(lookTarget, placer.getPlacementYaw(currentEntry.state()));
        } else {
            camera.lookAt(lookTarget);
        }

        // Stairs derive their state from the real player yaw. A generic timeout
        // must never place them while the camera is still crossing that angle.
        boolean aimReady = requiresFacing
                ? camera.isYawConverged(2.0f) && (camera.isConverged() || waitTicks > 14)
                : camera.isConverged() || waitTicks > 14;
        if (aimReady) {
            camera.stop();
            setState(BuildState.PLACING);
        } else if (waitTicks > 80) {
            camera.stop();
            handleNavigationFailure("не удалось стабильно навестись на грань блока");
        }
    }

    /**
     * PLACING: ставим блок.
     */
    private void tickPlacing() {
        if (currentEntry == null) {
            setState(BuildState.SORTING);
            return;
        }

        if (handleOccupiedCurrentTarget()) return;
        if (moveOutOfCurrentTargetIfNeeded()) return;

        // Проверяем, действительно ли нужный блок сейчас в руке
        if (!placer.isReady(currentEntry.state())) {
            setState(BuildState.LOOKING);
            return;
        }

        // Пробуем поставить
        boolean success = placer.placeBlock(currentEntry.pos(), currentEntry.state());

        if (success) {
            placementSentThisTick = true;
            schedulePlacementVerification(currentEntry);
            if (placer.requiresPlacementVerification(currentEntry.state())) {
                // Directional and half-height blocks are counted only after the
                // client world confirms their exact state. DOUBLE slabs return
                // here once more to receive their second legitimate click.
                addFirstQueuedEntry(currentEntry);
            } else {
                recordSuccessfulPlacement(currentEntry);
            }
            lastCategory = currentEntry.category();
            lastPlacedBlock = currentEntry.state().getBlock();
            placementFailed = false;
            placementFailureCount = 0;
            lastFailedPos = null;
            navigationFailures.remove(currentEntry.pos());

            // Определяем задержку
            if (placer.requiresPlacementVerification(currentEntry.state())) {
                waitThenSort(2);
            } else if (!cinematicFast && timing.shouldMakeMistake()) {
                // Имитация ошибки!
                waitTarget = timing.mistakeRecoveryDuration();
                setState(BuildState.MISTAKE);
            } else if (!cinematicFast && timing.shouldPause()) {
                // Микро-пауза «задумался»
                waitThenSort(timing.pauseDuration());
            } else {
                setState(BuildState.SORTING);
            }
        } else {
            BlockPos currentPos = currentEntry.pos();
            BlockPos obstruction = placer.consumePlacementObstruction();
            if (isRemovableObstruction(obstruction)) {
                beginBreaking(currentEntry, obstruction);
                return;
            }
            if (currentPos.equals(lastFailedPos)) {
                placementFailureCount++;
            } else {
                lastFailedPos = currentPos;
                placementFailureCount = 1;
            }

            // На первых 2 попытках даём 1 тик паузы, давая миру/пакетам обновиться без резких смен позиции
            if (placementFailureCount <= 2) {
                waitThenLook(1);
                return;
            }

            // На 3-й попытке пробуем сменить позицию игрока
            if (placementFailureCount == 3) {
                placementFailed = true;
                setState(BuildState.WALKING);
                return;
            }

            // Architectural fronts are atomic: a failed cell must not be
            // shuffled behind the rest of the layer or silently open the next Y.
            if (placementFailureCount >= 6) {
                if (sortMode == SortMode.ARCHITECTURAL) {
                    if (tryCreateTemporarySupport(currentEntry.workGroup(), currentPos)) {
                        BuildEntry blocked = currentEntry;
                        addFirstQueuedEntry(blocked);
                        currentEntry = null;
                        placementFailed = false;
                        placementFailureCount = 0;
                        lastFailedPos = null;
                        navigationFailures.remove(currentPos);
                        navigationRetryAfter.remove(currentPos);
                        setState(BuildState.SORTING);
                        return;
                    }

                    sendWarningMessage("placement_blocked",
                            "§c[HB] Не удалось установить обязательный блок "
                                    + currentPos.toShortString()
                                    + ". Блок не пропущен; текущий слой поставлен на паузу.");
                    movement.stop();
                    camera.stop();
                    placementFailed = true;
                    setState(BuildState.WALKING);
                    pauseBuild();
                    return;
                }

                sendMessage("§e[HB] ⚠ Не удалось установить блок на " + currentPos.toShortString() + ". Откладываю в конец очереди.");
                navigationRetryAfter.put(currentPos,
                        executorTicks + BASE_NAVIGATION_COOLDOWN_TICKS);
                activeBatch.remove(currentPos);
                offerQueuedEntry(currentEntry);
                currentEntry = null;
                placementFailed = false;
                placementFailureCount = 0;
                lastFailedPos = null;
                setState(BuildState.SORTING);
                return;
            }

            placementFailed = true;
            setState(BuildState.WALKING);
        }
    }

    /**
     * WAITING: ждём задержку, затем переходим к следующему блоку.
     */
    private void tickWaiting() {
        waitTicks++;

        if (waitTicks >= waitTarget) {
            waitTicks = 0;
            waitTarget = 0;
            BuildState nextState = resumeLookingAfterWait && currentEntry != null
                    ? BuildState.LOOKING
                    : resumeWalkingAfterWait && currentEntry != null
                            ? BuildState.WALKING
                            : BuildState.SORTING;
            resumeLookingAfterWait = false;
            resumeWalkingAfterWait = false;
            setState(nextState);
        }
    }

    private void waitThenSort(int ticks) {
        scheduleWait(ticks, false, false);
    }

    private void waitThenWalk(int ticks) {
        scheduleWait(ticks, false, true);
    }

    private void waitThenLook(int ticks) {
        scheduleWait(ticks, true, false);
    }

    private void scheduleWait(int ticks, boolean resumeLooking, boolean resumeWalking) {
        waitTarget = Math.max(1, ticks);
        resumeLookingAfterWait = resumeLooking;
        resumeWalkingAfterWait = resumeWalking;
        setState(BuildState.WAITING);
    }

    /**
     * MISTAKE: имитация ошибки — пауза, как будто осознаём ошибку.
     * В реальности мы просто ждём дольше обычного.
     * (Полная имитация с ломанием блока — TODO для продвинутой версии)
     */
    private void tickMistake() {
        waitTicks++;

        if (waitTicks >= waitTarget) {
            waitTicks = 0;
            waitTarget = 0;
            resumeLookingAfterWait = false;
            resumeWalkingAfterWait = false;
            setState(BuildState.SORTING);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  Утилиты
    // ════════════════════════════════════════════════════════════════════

    public boolean isPlacedSchematicBlock(BlockPos pos) {
        if (state == BuildState.IDLE) return false;
        Map<BlockPos, BlockState> abs = getAbsoluteBlocks();
        return abs.containsKey(pos)
                && !pendingEntries.containsKey(pos)
                && !placementVerifications.containsKey(pos);
    }

    /**
     * Проверяет, есть ли у блока опора (хотя бы один твёрдый сосед по 6 направлениям
     * или уже поставленный блок из схемы).
     */
    private boolean hasSupport(BlockPos pos) {
        if (client.world == null) return false;

        BlockPos[] neighbors = {
            pos.down(), pos.up(),
            pos.north(), pos.south(),
            pos.east(), pos.west()
        };

        for (BlockPos neighbor : neighbors) {
            BlockState neighborState = client.world.getBlockState(neighbor);
            if (!neighborState.isReplaceable()) {
                return true;
            }
            if (isPlacedSchematicBlock(neighbor)) {
                return true;
            }
        }
        return false;
    }

    private void beginWalking(boolean forceDifferent) {
        boolean retryingNavigation = navigationFailures.getOrDefault(currentEntry.pos(), 0) > 0;
        if (!movement.walkTo(currentEntry.pos(), currentEntry.state(),
                forceDifferent || retryingNavigation)) {
            handleNavigationFailure(movement.consumeFailureReason());
        }
    }

    private boolean moveOutOfCurrentTargetIfNeeded() {
        if (currentEntry == null
                || !placer.wouldPlacementIntersectPlayer(
                        currentEntry.pos(), currentEntry.state())) {
            return false;
        }

        boolean needsNewRoute = !movement.isActive() || state != BuildState.WALKING;
        camera.stop();
        placementFailed = true;
        if (state != BuildState.WALKING) {
            movement.stop();
            setState(BuildState.WALKING);
        }
        if (needsNewRoute && !movement.isActive()) {
            HumanBuilderMod.LOGGER.info(
                    "[HumanBuilder] Target {} intersects the player; moving to a safe standing point",
                    currentEntry.pos().toShortString());
            beginWalking(true);
        }
        return true;
    }

    private boolean handleOccupiedCurrentTarget() {
        if (currentEntry == null || client.world == null) return false;

        BlockState actual = client.world.getBlockState(currentEntry.pos());
        if (actual.isReplaceable()) return false;
        if (placer.canCompletePlacementState(actual, currentEntry.state())) return false;

        if (placer.matchesPlacementState(actual, currentEntry.state())) {
            movement.stop();
            camera.stop();
            if (currentEntry.temporary() || pendingEntries.containsKey(currentEntry.pos())) {
                recordSuccessfulPlacement(currentEntry);
            }
            lastCategory = currentEntry.category();
            navigationFailures.remove(currentEntry.pos());
            currentEntry = null;
            setState(BuildState.SORTING);
            return true;
        }

        if (currentEntry.temporary()) {
            HumanBuilderMod.LOGGER.warn(
                    "[HumanBuilder] Temporary support cell {} became occupied; replanning",
                    currentEntry.pos().toShortString());
            temporarySupportQueue.clear();
            currentEntry = null;
            movement.stop();
            camera.stop();
            setState(BuildState.SORTING);
            return true;
        }

        if (!placer.canSafelyBreak(currentEntry.pos())) {
            skipProtectedObstruction(currentEntry, currentEntry.pos());
            return true;
        }

        beginBreaking(currentEntry, currentEntry.pos());
        return true;
    }

    private void skipProtectedObstruction(BuildEntry entry, BlockPos obstruction) {
        movement.stop();
        camera.stop();
        if (entry.temporary()) {
            temporarySupportQueue.clear();
            currentEntry = null;
            placementFailed = false;
            clearBreakingState();
            sendWarningMessage("temporary_support_protected",
                    "§7[HB] Путь временной опоры затрагивает защищённый блок; ищу другой маршрут.");
            setState(BuildState.SORTING);
            return;
        }
        BlockPos dependent = placer.getLastProtectedDependent();
        String dependentText = dependent == null ? "\u0441\u043e\u0441\u0435\u0434\u043d\u0438\u0439 \u0437\u0430\u0432\u0438\u0441\u0438\u043c\u044b\u0439 \u0431\u043b\u043e\u043a"
                : "\u0431\u043b\u043e\u043a " + dependent.toShortString();
        if (sortMode == SortMode.ARCHITECTURAL && !clearingQueue.isEmpty()) {
            sendWarningMessage("demolition_blocked", "\u00a7c[HB] \u041f\u0440\u0435\u0434\u0432\u0430\u0440\u0438\u0442\u0435\u043b\u044c\u043d\u044b\u0439 \u0434\u0435\u043c\u043e\u043d\u0442\u0430\u0436 \u043e\u0441\u0442\u0430\u043d\u043e\u0432\u043b\u0435\u043d \u0443 "
                    + obstruction.toShortString() + ": \u043f\u043e\u0437\u0438\u0446\u0438\u044f \u0443\u0434\u0435\u0440\u0436\u0438\u0432\u0430\u0435\u0442 " + dependentText
                    + ". \u041f\u043e\u0441\u043b\u0435 \u0443\u0441\u0442\u0440\u0430\u043d\u0435\u043d\u0438\u044f \u043f\u0440\u0438\u0447\u0438\u043d\u044b \u0432\u044b\u043f\u043e\u043b\u043d\u0438\u0442\u0435 /humanbuilder resume.");
            currentEntry = null;
            placementFailed = false;
            clearBreakingState();
            pauseBuild();
            return;
        }
        sendWarningMessage("protected_obstruction", "\u00a7c[HB] \u041d\u0435 \u043b\u043e\u043c\u0430\u044e " + obstruction.toShortString()
                + ": \u043e\u043d \u0443\u0434\u0435\u0440\u0436\u0438\u0432\u0430\u0435\u0442 " + dependentText
                + ". \u0426\u0435\u043b\u044c \u043d\u0435 \u043f\u0440\u043e\u043f\u0443\u0449\u0435\u043d\u0430; \u0441\u0442\u0440\u043e\u0438\u0442\u0435\u043b\u044c\u0441\u0442\u0432\u043e \u043f\u043e\u0441\u0442\u0430\u0432\u043b\u0435\u043d\u043e \u043d\u0430 \u043f\u0430\u0443\u0437\u0443.");
        placementVerifications.remove(entry.pos());
        clearBreakingState();
        placementFailed = true;
        setState(BuildState.WALKING);
        pauseBuild();
    }

    private void handleNavigationFailure(String reason) {
        if (currentEntry == null) {
            setState(BuildState.SORTING);
            return;
        }
        String failureReason = reason == null || reason.isBlank()
                ? "маршрут завершился без диагностической причины"
                : reason;

        BlockPos failedPos = currentEntry.pos();
        BlockPos obstruction = placer.consumePlacementObstruction();
        BlockPos routeObstruction = movement.consumeBlockingObstacle();
        if (!isRemovableObstruction(obstruction)) obstruction = routeObstruction;
        if (isRemovableObstruction(obstruction)) {
            beginBreaking(currentEntry, obstruction);
            return;
        }
        if (hasUnverifiedPlacementForWorkGroup(currentEntry.workGroup())) {
            movement.stop();
            placementFailed = true;
            waitThenWalk(2);
            return;
        }
        int attempts = navigationFailures.merge(failedPos, 1, Integer::sum);
        movement.stop();

        if (currentEntry.temporary()) {
            navigationRetryAfter.put(failedPos,
                    executorTicks + navigationCooldownTicks(attempts));
            if (attempts >= 6) {
                sendWarningMessage("temporary_support_route",
                        "§e[HB] Не удалось подойти к временной опоре "
                                + failedPos.toShortString() + "; перестраиваю путь.");
                temporarySupportQueue.clear();
                currentEntry = null;
                placementFailed = false;
                setState(BuildState.SORTING);
                return;
            }
            offerQueuedEntry(currentEntry);
            currentEntry = null;
            placementFailed = false;
            setState(BuildState.SORTING);
            return;
        }

        if (sortMode == SortMode.ARCHITECTURAL
                && failureReason.contains("свободная позиция с видимой гранью")
                && tryCreateTemporarySupport(currentEntry.workGroup(), failedPos)) {
            BuildEntry blocked = currentEntry;
            navigationFailures.remove(failedPos);
            navigationRetryAfter.remove(failedPos);
            addFirstQueuedEntry(blocked);
            currentEntry = null;
            placementFailed = false;
            setState(BuildState.SORTING);
            return;
        }

        if (attempts >= 6) {
            sendWarningMessage("navigation_blocked",
                    "§c[HB] Не могу продолжить текущий слой у "
                            + failedPos.toShortString() + ": " + failureReason
                            + ". Блок не пропущен; строительство поставлено на паузу.");
            placementFailed = true;
            pauseBuild();
            return;
        }

        sendWarningMessage("navigation_retry",
                "§7[HB] Повторяю подход к " + failedPos.toShortString()
                        + " (попытка " + attempts + "): " + failureReason + ".");
        navigationRetryAfter.remove(failedPos);
        placementFailed = true;
        waitThenWalk(Math.min(20, 3 + attempts * 2));
    }

    private long navigationCooldownTicks(int attempts) {
        int exponent = Math.min(4, Math.max(0, attempts - 1));
        return Math.min(600L, BASE_NAVIGATION_COOLDOWN_TICKS * (1L << exponent));
    }

    /**
     * Берёт следующий блок из очереди. Пропускает уже установленные блоки.
     * Откладывает блоки без опоры в конец очереди (с защитой от бесконечного цикла).
     */
    private boolean advanceToNextBlock() {
        return advanceToNextBlock(false);
    }

    private boolean advanceToNextBlock(boolean allowBatch) {
        if (sortMode.isCinematic() && !clearingQueue.isEmpty() && client.world != null) {
            int maxChecks = clearingQueue.size();
            int checks = 0;
            while (!clearingQueue.isEmpty() && checks < maxChecks) {
                BlockPos pos = clearingQueue.peekFirst();
                checks++;
                net.minecraft.block.BlockState worldState = client.world.getBlockState(pos);
                BuildEntry entry = pendingEntries.get(pos);
                if (entry != null && (worldState.isReplaceable()
                        || placer.matchesPlacementState(worldState, entry.state())
                        || placer.canCompletePlacementState(worldState, entry.state()))) {
                    clearingQueue.pollFirst();
                    continue;
                }
                Long retryAt = navigationRetryAfter.get(pos);
                if (retryAt != null && executorTicks < retryAt) {
                    clearingQueue.pollFirst();
                    clearingQueue.offerLast(pos);
                    continue;
                }
                if (retryAt != null) navigationRetryAfter.remove(pos);
                if (entry == null) {
                    clearingQueue.pollFirst();
                    continue;
                }
                beginBreaking(entry, pos);
                return true;
            }
            if (!clearingQueue.isEmpty()) {
                waitThenSort(5);
                return false;
            }
        }

        if (!temporarySupportQueue.isEmpty()) {
            return advanceTemporarySupport();
        }

        if (shouldCleanTemporarySupports()) {
            if (executorTicks < temporarySupportCleanupRetryAt) {
                waitThenSort(5);
                return false;
            }
            beginTemporarySupportCleanup();
            return true;
        }

        if (!hasQueuedEntries()) {
            currentEntry = null;
            return false;
        }

        Integer queueLayer = usesLayerLock() ? layerQueues.firstKey() : null;
        Deque<BuildEntry> queue = queueLayer == null ? buildQueue : layerQueues.get(queueLayer);
        Integer lockedWorkGroup = sortMode == SortMode.ARCHITECTURAL
                ? queue.stream().mapToInt(BuildEntry::workGroup).min().orElse(-1)
                : null;

        // Normal blocks are placed optimistically for speed. Do not open a
        // newer architectural front until the server confirmed every packet
        // from the previous one; rejected cells are restored by verification.
        if (lockedWorkGroup != null) {
            int verifyingWorkGroup = placementVerifications.values().stream()
                    .map(PlacementVerification::entry)
                    .filter(entry -> !entry.temporary())
                    .mapToInt(BuildEntry::workGroup)
                    .min()
                    .orElse(Integer.MAX_VALUE);
            if (verifyingWorkGroup < lockedWorkGroup) {
                waitThenSort(1);
                return false;
            }
        }

        // Локальный умный поиск отключен для сохранения строгого концентрического обхода
        int maxChecks = queue.size();
        int checks = 0;
        boolean sawCoolingDownEntry = false;
        BuildEntry unsupportedFallback = null;

        while (!queue.isEmpty() && checks < maxChecks) {
            BuildEntry entry = queue.poll();
            checks++;

            if (!activeBatch.isEmpty() && !activeBatch.contains(entry.pos())) {
                queue.offer(entry);
                continue;
            }
            if (lockedWorkGroup != null && entry.workGroup() != lockedWorkGroup) {
                queue.offer(entry);
                continue;
            }

            Long retryAt = navigationRetryAfter.get(entry.pos());
            if (retryAt != null && executorTicks < retryAt) {
                sawCoolingDownEntry = true;
                queue.offer(entry);
                continue;
            }
            if (retryAt != null) navigationRetryAfter.remove(entry.pos());

            PlacementVerification verification = placementVerifications.get(entry.pos());
            if (verification != null && executorTicks < verification.dueTick()) {
                sawCoolingDownEntry = true;
                queue.offer(entry);
                continue;
            }

            if (allowBatch && activeBatch.isEmpty()) {
                beginAdjacentBatch(entry);
            }

            // Пропускаем, если на этой позиции уже стоит нужный блок или любой незаменяемый
            if (client.world != null) {
                BlockState worldState = client.world.getBlockState(entry.pos());
                if (placer.matchesPlacementState(worldState, entry.state())) {
                    recordSuccessfulPlacement(entry);
                    continue;
                } else if (worldState.getBlock() == entry.state().getBlock() && placer.canCompletePlacementState(worldState, entry.state())) {
                    currentEntry = entry;
                    lastPlacedBlock = entry.state().getBlock();
                    hotbarSyncAttempts = 0;
                    finishQueueAccess(queueLayer, queue);
                    return true;
                } else if (!worldState.isReplaceable()) {
                    finishQueueAccess(queueLayer, queue);
                    beginBreaking(entry, entry.pos());
                    return true;
                }
            }

            // Проверяем опору: есть ли хотя бы один твёрдый блок-сосед?
            if (!hasSupport(entry.pos())) {
                // Нет опоры — откладываем в конец очереди
                if (unsupportedFallback == null) unsupportedFallback = entry;
                queue.offer(entry);
                continue;
            }

            supportWorldRechecks.remove(entry.pos());
            currentEntry = entry;
            lastPlacedBlock = entry.state().getBlock();
            hotbarSyncAttempts = 0;
            finishQueueAccess(queueLayer, queue);
            return true;
        }

        finishQueueAccess(queueLayer, queue);

        if (allowBatch && !activeBatch.isEmpty()) {
            // The batch currently has no placeable member. Release it so another
            // material can provide support, then retry the same layer once.
            activeBatch.clear();
            return advanceToNextBlock(false);
        }

        if (queueLayer != null && !layerQueues.containsKey(queueLayer) && hasQueuedEntries()) {
            return advanceToNextBlock(allowBatch);
        }

        if (lockedWorkGroup != null
                && !hasPendingWorkGroup(lockedWorkGroup)
                && hasQueuedEntries()) {
            return advanceToNextBlock(false);
        }

        if (sawCoolingDownEntry && hasQueuedEntries()) {
            waitThenSort(5);
            return false;
        }

        if (lockedWorkGroup != null
                && hasUnverifiedPlacementForWorkGroup(lockedWorkGroup)) {
            waitThenSort(2);
            return false;
        }

        if (lockedWorkGroup != null && hasPendingWorkGroup(lockedWorkGroup)) {
            SupportBootstrapResult bootstrap = findArchitecturalSupportBootstrap(lockedWorkGroup);
            if (bootstrap.entry() != null) {
                return activateSupportBootstrap(bootstrap.entry());
            }
            if (bootstrap.deferred()) {
                waitThenSort(5);
                return false;
            }
            if (tryCreateTemporarySupport(lockedWorkGroup, null)) {
                return advanceToNextBlock(false);
            }
        }

        if (unsupportedFallback != null) {
            int rechecks = supportWorldRechecks.merge(
                    unsupportedFallback.pos(), 1, Integer::sum);
            if (rechecks <= MAX_SUPPORT_WORLD_RECHECKS) {
                HumanBuilderMod.LOGGER.debug(
                        "[HumanBuilder] Waiting for support world sync at {} ({}/{})",
                        unsupportedFallback.pos().toShortString(),
                        rechecks, MAX_SUPPORT_WORLD_RECHECKS);
                waitThenSort(3);
                return false;
            }
        }

        // Если прошли полный круг и ни один блок не получил опору
        if (hasQueuedEntries()) {
            int blockedGroup = lockedWorkGroup == null ? -1 : lockedWorkGroup;
            long blockedBlocks = lockedWorkGroup == null
                    ? pendingEntries.size()
                    : pendingEntries.values().stream()
                            .filter(entry -> entry.workGroup() == lockedWorkGroup)
                            .count();
            String blockedPosition = unsupportedFallback == null
                    ? "неизвестно"
                    : unsupportedFallback.pos().toShortString();
            sendMessage("§c[HB] ⚠ Не найден путь опоры для рабочего участка #"
                    + blockedGroup + " (" + blockedBlocks + " блоков), цель "
                    + blockedPosition + ".");
            sendMessage("§7[HB] Проверка мира и безопасная временная опора исчерпаны; участок оставлен без пропуска блоков.");
            pauseBuild();
            return false;
        }

        currentEntry = null;
        return false;
    }

    private boolean advanceTemporarySupport() {
        if (client.world == null) return false;
        while (!temporarySupportQueue.isEmpty()) {
            BuildEntry entry = temporarySupportQueue.pollFirst();
            BlockState actual = client.world.getBlockState(entry.pos());
            if (placer.matchesPlacementState(actual, entry.state())) {
                recordSuccessfulPlacement(entry);
                continue;
            }
            if (!actual.isAir()) {
                HumanBuilderMod.LOGGER.warn(
                        "[HumanBuilder] Temporary support path was occupied at {}; replanning",
                        entry.pos().toShortString());
                temporarySupportQueue.clear();
                return advanceToNextBlock(false);
            }
            if (!hasSupport(entry.pos())) {
                int supportWaits = navigationFailures.merge(entry.pos(), 1, Integer::sum);
                if (supportWaits >= 20) {
                    navigationFailures.remove(entry.pos());
                    temporarySupportQueue.clear();
                    sendWarningMessage("temporary_support_stale",
                            "§7[HB] Основание временной опоры изменилось; пересчитываю маршрут.");
                    return advanceToNextBlock(false);
                }
                temporarySupportQueue.addFirst(entry);
                waitThenSort(3);
                return false;
            }

            navigationFailures.remove(entry.pos());
            currentEntry = entry;
            lastCategory = entry.category();
            lastPlacedBlock = entry.state().getBlock();
            hotbarSyncAttempts = 0;
            return true;
        }
        return advanceToNextBlock(false);
    }

    private boolean shouldCleanTemporarySupports() {
        if (temporarySupports.isEmpty()) {
            temporarySupportOwnerGroup = null;
            return false;
        }
        return temporarySupportOwnerGroup != null
                && !hasPendingWorkGroup(temporarySupportOwnerGroup);
    }

    private boolean tryCreateTemporarySupport(
            int lockedWorkGroup,
            BlockPos preferredTarget
    ) {
        if (client.world == null || !temporarySupportQueue.isEmpty()) return false;

        List<BuildEntry> roots = pendingEntries.values().stream()
                .filter(entry -> entry.workGroup() == lockedWorkGroup)
                .filter(entry -> preferredTarget == null
                        || entry.pos().equals(preferredTarget))
                .sorted(Comparator
                        .comparingDouble((BuildEntry entry) -> client.player == null
                                ? 0.0
                                : entry.pos().getSquaredDistance(client.player.getBlockPos()))
                        .thenComparingInt(entry -> entry.pos().getY())
                        .thenComparingInt(entry -> entry.pos().getX())
                        .thenComparingInt(entry -> entry.pos().getZ()))
                .limit(64)
                .toList();

        BuildEntry selectedRoot = null;
        List<BlockPos> selectedPath = List.of();
        // A scaffold must have exactly one full-cube state. Reusing the target
        // material made slabs, stairs and other shaped blocks invalid supports.
        BlockState selectedState = temporarySupportState();
        for (BuildEntry root : roots) {
            List<BlockPos> path = findTemporarySupportPath(
                    root.pos(), lockedWorkGroup);
            if (!path.isEmpty()
                    && (selectedPath.isEmpty() || path.size() < selectedPath.size())) {
                selectedRoot = root;
                selectedPath = path;
                if (path.size() == 1) break;
            }
        }
        if (selectedRoot == null) return false;

        temporarySupportOwnerGroup = lockedWorkGroup;
        for (BlockPos pos : selectedPath) {
            temporarySupportQueue.addLast(new BuildEntry(
                    pos.toImmutable(), selectedState, selectedRoot.category(),
                    lockedWorkGroup, true));
        }
        sendWarningMessage("temporary_support_created",
                "§7[HB] Создаю временную опору из §f"
                        + selectedState.getBlock().getName().getString()
                        + "§7: " + selectedPath.size() + " блок(а). После участка она будет убрана.");
        HumanBuilderMod.LOGGER.info(
                "[HumanBuilder] Temporary support for group {} targets {} and uses {} blocks",
                lockedWorkGroup, selectedRoot.pos().toShortString(), selectedPath.size());
        return true;
    }

    private static BlockState temporarySupportState() {
        return Blocks.COBBLESTONE.getDefaultState();
    }

    private List<BlockPos> findTemporarySupportPath(
            BlockPos target,
            int lockedWorkGroup
    ) {
        return TemporarySupportPathfinder.find(
                target,
                pos -> isTemporarySupportCell(pos, lockedWorkGroup),
                this::hasUsableWorldSupport,
                MAX_TEMPORARY_SUPPORT_LENGTH,
                MAX_TEMPORARY_SUPPORT_SEARCH_NODES);
    }

    private boolean isTemporarySupportCell(BlockPos pos, int lockedWorkGroup) {
        if (client.world == null || !client.world.getBlockState(pos).isAir()) return false;

        BuildEntry futureEntry = pendingEntries.get(pos);
        if (futureEntry != null) {
            // A later work front may temporarily carry the current one. It is
            // cleaned before that front opens, so its real state is not lost.
            return futureEntry.workGroup() != lockedWorkGroup;
        }
        return !getAbsoluteBlocks().containsKey(pos);
    }

    private boolean hasUsableWorldSupport(BlockPos pos) {
        if (client.world == null) return false;
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = pos.offset(direction);
            BlockState state = client.world.getBlockState(neighbor);
            if (!state.isReplaceable()
                    && !state.getOutlineShape(client.world, neighbor).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds the nearest pending dependency that can be placed now. The search
     * starts at the locked work front and walks through the schematic graph,
     * so it cannot jump to an unrelated floor or decoration block.
     */
    private SupportBootstrapResult findArchitecturalSupportBootstrap(int lockedWorkGroup) {
        if (client.world == null) return SupportBootstrapResult.NONE;

        List<BuildEntry> roots = pendingEntries.values().stream()
                .filter(entry -> entry.workGroup() == lockedWorkGroup)
                .sorted(Comparator.comparingInt((BuildEntry entry) -> entry.pos().getY())
                        .thenComparingInt(entry -> entry.pos().getX())
                        .thenComparingInt(entry -> entry.pos().getZ()))
                .toList();
        if (roots.isEmpty()) return SupportBootstrapResult.NONE;

        BlockCategory targetCategory = roots.stream()
                .map(BuildEntry::category)
                .min(Comparator.comparingInt(BlockCategory::getPriority))
                .orElse(BlockCategory.PILLAR);
        int lockedMaxY = roots.stream()
                .mapToInt(entry -> entry.pos().getY())
                .max()
                .orElse(Integer.MIN_VALUE);

        List<EnumSet<BlockCategory>> passes = supportSearchPasses(targetCategory);
        boolean deferred = false;
        for (EnumSet<BlockCategory> allowed : passes) {
            SupportBootstrapResult result = searchSupportBootstrap(
                    roots, lockedWorkGroup, lockedMaxY, allowed);
            if (result.entry() != null) return result;
            deferred |= result.deferred();
        }
        return new SupportBootstrapResult(null, deferred);
    }

    private List<EnumSet<BlockCategory>> supportSearchPasses(BlockCategory targetCategory) {
        EnumSet<BlockCategory> normal = EnumSet.of(
                BlockCategory.FOUNDATION, BlockCategory.PILLAR);
        if (targetCategory.getPriority() >= BlockCategory.WALL.getPriority()) {
            normal.add(BlockCategory.WALL);
        }
        if (targetCategory.getPriority() >= BlockCategory.ROOF.getPriority()) {
            normal.add(BlockCategory.ROOF);
        }
        if (targetCategory.getPriority() >= BlockCategory.WINDOW.getPriority()) {
            normal.add(BlockCategory.WINDOW);
        }
        if (targetCategory.getPriority() >= BlockCategory.INTERIOR_WALL.getPriority()) {
            normal.add(BlockCategory.INTERIOR_WALL);
        }
        if (targetCategory == BlockCategory.DECOR) normal.add(BlockCategory.DECOR);

        List<EnumSet<BlockCategory>> passes = new ArrayList<>();
        passes.add(EnumSet.copyOf(normal));
        if (!normal.contains(BlockCategory.WALL)) {
            EnumSet<BlockCategory> withShell = EnumSet.copyOf(normal);
            withShell.add(BlockCategory.WALL);
            passes.add(withShell);
        }
        return passes;
    }

    private SupportBootstrapResult searchSupportBootstrap(
            List<BuildEntry> roots,
            int lockedWorkGroup,
            int lockedMaxY,
            EnumSet<BlockCategory> allowed
    ) {
        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        for (BuildEntry root : roots) {
            if (visited.add(root.pos())) open.addLast(root.pos());
        }

        boolean deferred = false;
        Direction[] directions = {
            Direction.DOWN, Direction.NORTH, Direction.SOUTH,
            Direction.WEST, Direction.EAST, Direction.UP
        };
        while (!open.isEmpty()) {
            BlockPos currentPos = open.removeFirst();
            BuildEntry current = pendingEntries.get(currentPos);
            if (current != null
                    && current.workGroup() != lockedWorkGroup
                    && current.pos().getY() <= lockedMaxY
                    && allowed.contains(current.category())
                    && hasSupport(currentPos)) {
                if (isSupportBootstrapReady(currentPos)) {
                    return new SupportBootstrapResult(current, false);
                }
                deferred = true;
            }

            for (Direction direction : directions) {
                BuildEntry neighbor = pendingEntries.get(currentPos.offset(direction));
                if (neighbor == null
                        || neighbor.pos().getY() > lockedMaxY
                        || !allowed.contains(neighbor.category())) continue;
                if (visited.add(neighbor.pos())) open.addLast(neighbor.pos());
            }
        }
        return new SupportBootstrapResult(null, deferred);
    }

    private boolean isSupportBootstrapReady(BlockPos pos) {
        Long retryAt = navigationRetryAfter.get(pos);
        if (retryAt != null && executorTicks < retryAt) return false;
        return !placementVerifications.containsKey(pos);
    }

    private boolean activateSupportBootstrap(BuildEntry entry) {
        buildQueue.removeIf(queued -> queued.pos().equals(entry.pos()));
        navigationRetryAfter.remove(entry.pos());
        activeBatch.clear();

        if (client.world != null) {
            BlockState worldState = client.world.getBlockState(entry.pos());
            if (placer.matchesPlacementState(worldState, entry.state())) {
                recordSuccessfulPlacement(entry);
                return advanceToNextBlock(false);
            }
            if (!worldState.isReplaceable()
                    && !placer.canCompletePlacementState(worldState, entry.state())) {
                beginBreaking(entry, entry.pos());
                return true;
            }
        }

        HumanBuilderMod.LOGGER.debug(
                "[HumanBuilder] Building support dependency {} from work group {}",
                entry.pos().toShortString(), entry.workGroup());
        currentEntry = entry;
        // A dependency is not a real phase transition, so do not add a long
        // cinematic pause before immediately returning to the locked front.
        lastCategory = entry.category();
        lastPlacedBlock = entry.state().getBlock();
        hotbarSyncAttempts = 0;
        return true;
    }

    /**
     * Пересобирает очередь блоков из текущей относительной схемы + origin.
     */
    private void rebuildQueue() {
        Map<BlockPos, BlockState> absoluteBlocks = getAbsoluteBlocks();
        BlockPos playerPos = client.player != null ? client.player.getBlockPos() : BlockPos.ORIGIN;
        List<BuildEntry> sorted = sorter.sort(absoluteBlocks, playerPos, sortMode);
        initializeBuildQueues(sorted);
        totalBlocks = sorted.size();
        blocksPlaced = 0;
        currentEntry = null;
        clearBreakingState();
        placementFailed = false;
        placementFailureCount = 0;
        lastFailedPos = null;
        navigationFailures.clear();
        navigationRetryAfter.clear();
        supportWorldRechecks.clear();
        lastPlacedBlock = null;
        activeBatch.clear();
        hotbarSyncAttempts = 0;

        if (state != BuildState.PAUSED) {
            setState(BuildState.SORTING);
        } else {
            savedStateBeforePause = BuildState.SORTING;
        }
    }

    private void setState(BuildState newState) {
        boolean changed = this.state != newState;
        if (changed) {
            HumanBuilderMod.LOGGER.debug("[HumanBuilder] State {} -> {} (target={})",
                    this.state, newState,
                    currentEntry == null ? "none" : currentEntry.pos().toShortString());
            this.state = newState;
            this.waitTicks = 0;
            this.stateEnteredAtTick = executorTicks;
            if (newState != BuildState.WAITING) {
                resumeLookingAfterWait = false;
                resumeWalkingAfterWait = false;
            }
        }
        boolean cinematicRoute = sortMode.isCinematic();
        this.movement.setCinematicMode(cinematicRoute);
        this.movement.setCinematicFast(this.cinematicFast);
        this.placer.setLineOfSightRequired(cinematicRoute && !cinematicFast);
    }

    private void updateDiagnostics() {
        long stateTicks = Math.max(0L, executorTicks - stateEnteredAtTick);
        if (state == BuildState.WAITING
                && stateTicks > Math.max(40L, waitTarget + 20L)) {
            HumanBuilderMod.LOGGER.error(
                    "[HumanBuilder] Waiting watchdog recovered state after {} ticks "
                            + "(target={}, requested={})",
                    stateTicks,
                    currentEntry == null ? "none" : currentEntry.pos().toShortString(),
                    waitTarget);
            sendWarningMessage("waiting_watchdog",
                    "§7[HB] Восстанавливаю зависшее ожидание и продолжаю текущую цель.");
            BuildState recoveredState = resumeLookingAfterWait && currentEntry != null
                    ? BuildState.LOOKING
                    : resumeWalkingAfterWait && currentEntry != null
                            ? BuildState.WALKING
                            : BuildState.SORTING;
            resumeLookingAfterWait = false;
            resumeWalkingAfterWait = false;
            waitTarget = 0;
            setState(recoveredState);
            return;
        }

        BlockPos target = currentEntry == null ? null : currentEntry.pos();
        if (!Objects.equals(target, diagnosticTarget) || state != diagnosticState) {
            diagnosticTarget = target == null ? null : target.toImmutable();
            diagnosticState = state;
            diagnosticTargetTicks = 0;
            return;
        }

        if (target == null) return;
        diagnosticTargetTicks++;
        if (state == BuildState.WALKING && diagnosticTargetTicks >= WALKING_WATCHDOG_TICKS) {
            HumanBuilderMod.LOGGER.error(
                    "[HumanBuilder] Walking watchdog recovered target {} after {} ticks",
                    target.toShortString(), diagnosticTargetTicks);
            sendWarningMessage("walking_watchdog",
                    "§7[HB] Маршрут к " + target.toShortString()
                            + " не завершился вовремя; автоматически меняю подход.");
            diagnosticTargetTicks = 0;
            movement.failActiveRoute("watchdog: маршрут слишком долго не завершался");
            return;
        }
        if (diagnosticTargetTicks >= 200 && diagnosticTargetTicks % 200 == 0) {
            HumanBuilderMod.LOGGER.warn(
                    "[HumanBuilder] No completed progress for {} ticks: state={}, target={}, route={}",
                    diagnosticTargetTicks, state, target.toShortString(), movement.describeRoute());
        }
    }

    private void invalidateAbsoluteBlocksCache() {
        absoluteBlocksCache = Collections.emptyMap();
        absoluteBlocksCacheOrigin = null;
    }

    private void beginAdjacentBatch(BuildEntry seed) {
        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        open.add(seed.pos());
        while (!open.isEmpty()) {
            BlockPos pos = open.removeFirst();
            BuildEntry candidate = pendingEntries.get(pos);
            if (candidate == null
                    || candidate.pos().getY() != seed.pos().getY()
                    || !placer.canBatchPlacementStates(candidate.state(), seed.state())
                    || !canJoinAdjacentBatch(seed.pos(), pos)
                    || !activeBatch.add(pos)) continue;
            open.addLast(pos.east());
            open.addLast(pos.west());
            open.addLast(pos.south());
            open.addLast(pos.north());
        }
    }

    private boolean canJoinAdjacentBatch(BlockPos seed, BlockPos candidate) {
        return true;
    }

    private void initializeBuildQueues(Collection<BuildEntry> entries) {
        buildQueue = new ArrayDeque<>();
        layerQueues.clear();
        placementVerifications.clear();
        clearingQueue.clear();
        temporarySupportQueue.clear();
        temporarySupports.clear();
        temporarySupportCleanupQueue.clear();
        temporarySupportOwnerGroup = null;
        temporarySupportCleanupRetryAt = 0L;

        if (sortMode == SortMode.ARCHITECTURAL && client.world != null) {
            for (BuildEntry entry : entries) {
                BlockState actual = client.world.getBlockState(entry.pos());
                if (!actual.isReplaceable()
                        && !placer.matchesPlacementState(actual, entry.state())
                        && !placer.canCompletePlacementState(actual, entry.state())) {
                    clearingQueue.offerLast(entry.pos().toImmutable());
                }
            }
        }

        if (usesLayerLock()) {
            for (BuildEntry entry : entries) {
                layerQueues
                        .computeIfAbsent(entry.pos().getY(), ignored -> new ArrayDeque<>())
                        .offer(entry);
            }
        } else {
            buildQueue.addAll(entries);
        }
        resetPendingEntries(entries);
    }

    private boolean hasPendingWorkGroup(int workGroup) {
        for (BuildEntry entry : pendingEntries.values()) {
            if (entry.workGroup() == workGroup) return true;
        }
        return false;
    }

    private boolean hasUnverifiedPlacementForWorkGroup(int workGroup) {
        return placementVerifications.values().stream()
                .map(PlacementVerification::entry)
                .anyMatch(entry -> entry.workGroup() == workGroup);
    }

    private boolean hasQueuedEntries() {
        return usesLayerLock() ? !layerQueues.isEmpty() : !buildQueue.isEmpty();
    }

    private void offerQueuedEntry(BuildEntry entry) {
        if (entry.temporary()) {
            temporarySupportQueue.offerLast(entry);
            return;
        }
        if (usesLayerLock()) {
            layerQueues.computeIfAbsent(entry.pos().getY(), ignored -> new ArrayDeque<>()).offer(entry);
        } else {
            buildQueue.offer(entry);
        }
    }

    private void addFirstQueuedEntry(BuildEntry entry) {
        if (entry.temporary()) {
            temporarySupportQueue.addFirst(entry);
            return;
        }
        if (usesLayerLock()) {
            layerQueues.computeIfAbsent(entry.pos().getY(), ignored -> new ArrayDeque<>()).addFirst(entry);
        } else {
            buildQueue.addFirst(entry);
        }
    }

    private void finishQueueAccess(Integer layer, Deque<BuildEntry> queue) {
        if (layer != null && queue.isEmpty()) layerQueues.remove(layer, queue);
    }

    private void resetPendingEntries(Collection<BuildEntry> entries) {
        pendingEntries.clear();
        pendingEntriesByChunk.clear();
        for (BuildEntry entry : entries) {
            pendingEntries.put(entry.pos(), entry);
            pendingEntriesByChunk
                    .computeIfAbsent(chunkKey(entry.pos().getX() >> 4, entry.pos().getZ() >> 4),
                            ignored -> new HashMap<>())
                    .put(entry.pos(), entry);
        }
    }

    private void recordSuccessfulPlacement(BuildEntry entry) {
        if (entry.temporary()) {
            temporarySupports.put(entry.pos().toImmutable(), entry.state());
            navigationFailures.remove(entry.pos());
            navigationRetryAfter.remove(entry.pos());
            supportWorldRechecks.remove(entry.pos());
            return;
        }
        if (markEntryComplete(entry)) blocksPlaced++;
    }

    private boolean markEntryComplete(BuildEntry entry) {
        if (pendingEntries.remove(entry.pos()) == null) return false;
        navigationFailures.remove(entry.pos());
        navigationRetryAfter.remove(entry.pos());
        supportWorldRechecks.remove(entry.pos());
        long chunk = chunkKey(entry.pos().getX() >> 4, entry.pos().getZ() >> 4);
        Map<BlockPos, BuildEntry> chunkEntries = pendingEntriesByChunk.get(chunk);
        if (chunkEntries != null) {
            chunkEntries.remove(entry.pos());
            if (chunkEntries.isEmpty()) pendingEntriesByChunk.remove(chunk);
        }
        activeBatch.remove(entry.pos());
        return true;
    }

    private void schedulePlacementVerification(BuildEntry entry) {
        placementVerifications.put(entry.pos(), new PlacementVerification(
                entry, executorTicks + requiredPlacementVerificationTicks()));
    }

    private void processPlacementVerifications() {
        if (placementVerifications.isEmpty() || client.world == null) return;

        Iterator<Map.Entry<BlockPos, PlacementVerification>> iterator =
                placementVerifications.entrySet().iterator();
        List<BuildEntry> rejected = new ArrayList<>();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, PlacementVerification> pending = iterator.next();
            PlacementVerification verification = pending.getValue();
            if (executorTicks < verification.dueTick()) continue;

            iterator.remove();
            BuildEntry entry = verification.entry();
            BlockState actual = client.world.getBlockState(entry.pos());
            if (placer.matchesPlacementState(actual, entry.state())) continue;

            if (entry.temporary()) {
                temporarySupports.remove(entry.pos());
                if (currentEntry == null || !currentEntry.pos().equals(entry.pos())) {
                    temporarySupportQueue.addFirst(entry);
                }
                HumanBuilderMod.LOGGER.warn(
                        "[HumanBuilder] Server rejected temporary support at {}; retrying",
                        entry.pos().toShortString());
                continue;
            }

            // Special blocks may already be queued for their second click or
            // orientation correction. Only restore entries that were counted
            // as complete and removed from the normal queue.
            if (!pendingEntries.containsKey(entry.pos())
                    && (currentEntry == null || !currentEntry.pos().equals(entry.pos()))) {
                rejected.add(entry);
            }
        }

        for (BuildEntry entry : rejected) {
            restorePendingEntry(entry);
            blocksPlaced = Math.max(0, blocksPlaced - 1);
            navigationFailures.remove(entry.pos());
            HumanBuilderMod.LOGGER.warn(
                    "[HumanBuilder] Server rejected or reverted placement at {}; scheduling a rebuild",
                    entry.pos().toShortString());
        }
    }

    private void restorePendingEntry(BuildEntry entry) {
        if (entry.temporary()) {
            temporarySupportQueue.addFirst(entry);
            return;
        }
        if (pendingEntries.putIfAbsent(entry.pos(), entry) != null) return;
        pendingEntriesByChunk
                .computeIfAbsent(chunkKey(entry.pos().getX() >> 4, entry.pos().getZ() >> 4),
                        ignored -> new HashMap<>())
                .put(entry.pos(), entry);
        offerQueuedEntry(entry);
    }

    private int requiredPlacementVerificationTicks() {
        int latency = 0;
        if (client.player != null && client.getNetworkHandler() != null) {
            var playerEntry = client.getNetworkHandler().getPlayerListEntry(client.player.getUuid());
            if (playerEntry != null) latency = Math.max(0, playerEntry.getLatency());
        }
        return Math.max(3, Math.min(12, 2 + (latency + 49) / 50));
    }

    private boolean usesLayerLock() {
        return sortMode == SortMode.LAYERED;
    }

    private void rebuildPreviewIndex() {
        previewEntriesByChunk.clear();
        for (BuildEntry entry : previewRelativeEntries) {
            previewEntriesByChunk
                    .computeIfAbsent(chunkKey(entry.pos().getX() >> 4, entry.pos().getZ() >> 4),
                            ignored -> new ArrayList<>())
                    .add(entry);
        }
    }

    private long chunkKey(int chunkX, int chunkZ) {
        return (chunkX & 0xffffffffL) | ((chunkZ & 0xffffffffL) << 32);
    }

    private void beginTemporarySupportCleanup() {
        temporarySupportCleanupQueue.clear();
        List<BlockPos> positions = new ArrayList<>(temporarySupports.keySet());
        Collections.reverse(positions);
        temporarySupportCleanupQueue.addAll(positions);
        movement.stop();
        camera.stop();
        clearBreakingState();
        setState(BuildState.CLEANING_SUPPORTS);
    }

    private void tickTemporarySupportCleanup() {
        if (client.world == null || client.player == null) {
            setState(BuildState.SORTING);
            return;
        }

        if (blockToBreak == null) {
            while (!temporarySupportCleanupQueue.isEmpty()) {
                BlockPos candidate = temporarySupportCleanupQueue.peekFirst();
                BlockState expected = temporarySupports.get(candidate);
                BlockState actual = client.world.getBlockState(candidate);
                if (expected == null || actual.isAir()
                        || actual.getBlock() != expected.getBlock()) {
                    temporarySupportCleanupQueue.pollFirst();
                    temporarySupports.remove(candidate);
                    continue;
                }
                selectBreakTarget(candidate);
                break;
            }

            if (blockToBreak == null) {
                temporarySupportOwnerGroup = null;
                temporarySupportCleanupRetryAt = 0L;
                temporarySupportCleanupQueue.clear();
                clearBreakingState();
                setState(BuildState.SORTING);
                return;
            }
        }

        BlockState actual = client.world.getBlockState(blockToBreak);
        BlockState expected = temporarySupports.get(blockToBreak);
        if (actual.isAir() || expected == null || actual.getBlock() != expected.getBlock()) {
            breakConfirmationTicks++;
            if (breakConfirmationTicks < requiredBreakConfirmationTicks()) return;
            temporarySupports.remove(blockToBreak);
            temporarySupportCleanupQueue.removeFirstOccurrence(blockToBreak);
            blockToBreak = null;
            breakConfirmationTicks = 0;
            breakAttempts = 0;
            movement.stop();
            camera.stop();
            return;
        }
        breakConfirmationTicks = 0;

        if (!placer.canSafelyBreak(blockToBreak)) {
            deferTemporarySupportCleanup("опора пока удерживает зависимый блок");
            return;
        }

        if (!placer.canBreakFromCurrentPosition(blockToBreak)) {
            camera.stop();
            if (!movement.isActive()) {
                if (movement.hasRouteFailure()) {
                    deferTemporarySupportCleanup(movement.consumeFailureReason());
                    return;
                }
                if (!movement.walkToBreak(blockToBreak)) {
                    deferTemporarySupportCleanup(movement.consumeFailureReason());
                }
            }
            return;
        }

        camera.lookAt(placer.getBreakLookTarget(blockToBreak));
        float tolerance = sortMode.isCinematic() ? 18.0f : 4.0f;
        if (!isCameraLookingAtTarget(tolerance) && breakTicks++ < 12) return;

        movement.stop();
        boolean accepted = placer.breakBlock(blockToBreak);
        breakAttempts++;
        breakTicks = 0;
        if (!accepted && breakAttempts >= 5) {
            deferTemporarySupportCleanup("сервер отклонил демонтаж временной опоры");
        }
    }

    private void deferTemporarySupportCleanup(String reason) {
        movement.stop();
        camera.stop();
        blockToBreak = null;
        temporarySupportCleanupQueue.clear();
        temporarySupportCleanupRetryAt = executorTicks + 100L;
        OptionalInt nextGroup = pendingEntries.values().stream()
                .mapToInt(BuildEntry::workGroup)
                .min();
        if (nextGroup.isPresent()) temporarySupportOwnerGroup = nextGroup.getAsInt();
        sendWarningMessage("temporary_support_cleanup",
                "§7[HB] Временную опору пока нельзя убрать: " + reason
                        + ". Повторю после следующего участка.");
        setState(BuildState.SORTING);
    }

    private void beginBreaking(BuildEntry entry, BlockPos obstruction) {
        if (!sortMode.isCinematic()) {
            movement.stop();
            camera.stop();
        }
        clearBreakingState();
        if (obstruction.equals(entry.pos())) placementVerifications.remove(entry.pos());
        currentEntry = entry;
        navigationRetryAfter.remove(entry.pos());
        BlockPos rootObstruction = obstruction.toImmutable();
        breakTargetStack.addFirst(rootObstruction);
        visitedBreakTargets.add(rootObstruction);
        selectBreakTarget(rootObstruction);
        HumanBuilderMod.LOGGER.info("[HumanBuilder] Removing obstruction {} for build target {}",
                blockToBreak.toShortString(), entry.pos().toShortString());
        setState(BuildState.BREAKING);
    }

    private void selectBreakTarget(BlockPos obstruction) {
        if (!sortMode.isCinematic()) {
            movement.stop();
            camera.stop();
        }
        blockToBreak = obstruction.toImmutable();
        breakTicks = 0;
        breakAttempts = 0;
        breakNavigationFailures = 0;
        breakConfirmationTicks = 0;
    }

    private void clearBreakingState() {
        blockToBreak = null;
        breakTargetStack.clear();
        visitedBreakTargets.clear();
        breakTicks = 0;
        breakAttempts = 0;
        breakNavigationFailures = 0;
        breakConfirmationTicks = 0;
    }

    private boolean resumeParentBreakTarget() {
        if (blockToBreak != null) breakTargetStack.removeFirstOccurrence(blockToBreak);
        if (breakTargetStack.isEmpty()) return false;

        BlockPos parent = breakTargetStack.peekFirst();
        selectBreakTarget(parent);
        HumanBuilderMod.LOGGER.info(
                "[HumanBuilder] Secondary obstruction removed; returning to {}",
                parent.toShortString());
        return true;
    }

    private boolean followSecondaryBreakTarget(BlockPos obstruction, String reason) {
        if (!isRemovableObstruction(obstruction) || obstruction.equals(blockToBreak)) return false;
        BlockPos next = obstruction.toImmutable();
        if (visitedBreakTargets.contains(next) || breakTargetStack.size() >= MAX_BREAK_CHAIN) {
            HumanBuilderMod.LOGGER.warn(
                    "[HumanBuilder] Refusing cyclic/deep obstruction switch {} -> {} (depth={})",
                    blockToBreak.toShortString(), next.toShortString(), breakTargetStack.size());
            return false;
        }

        breakTargetStack.addFirst(next);
        visitedBreakTargets.add(next);
        BlockPos previous = blockToBreak;
        selectBreakTarget(next);
        HumanBuilderMod.LOGGER.info(
                "[HumanBuilder] Obstruction {} blocks access to {}; depth={}: {}",
                next.toShortString(), previous.toShortString(), breakTargetStack.size(), reason);
        return true;
    }

    private boolean followProtectedDependent(BlockPos dependent) {
        if (dependent == null || dependent.equals(blockToBreak) || client.world == null) return false;
        if (client.world.getBlockState(dependent).isReplaceable()) return false;

        // During the demolition pre-pass every schematic entry is still pending,
        // so a temporarily removed crystal or attachment will be rebuilt later.
        if (getAbsoluteBlocks().containsKey(dependent) && !pendingEntries.containsKey(dependent)) {
            return false;
        }
        if (visitedBreakTargets.contains(dependent) || breakTargetStack.size() >= MAX_BREAK_CHAIN) {
            return false;
        }

        BlockPos next = dependent.toImmutable();
        breakTargetStack.addFirst(next);
        visitedBreakTargets.add(next);
        BlockPos previous = blockToBreak;
        selectBreakTarget(next);
        HumanBuilderMod.LOGGER.info(
                "[HumanBuilder] Removing dependent {} before its support {}; depth={}",
                next.toShortString(), previous.toShortString(), breakTargetStack.size());
        return true;
    }

    private void recordBreakNavigationFailure(String reason) {
        breakNavigationFailures++;
        if (breakNavigationFailures >= MAX_BREAK_NAVIGATION_FAILURES) {
            deferCurrentBreakTarget(reason);
        }
    }

    private void deferCurrentBreakTarget(String reason) {
        BuildEntry deferred = currentEntry;
        movement.stop();
        camera.stop();
        if (deferred != null) {
            int attempts = navigationFailures.merge(deferred.pos(), 1, Integer::sum);
            if (attempts >= 6) {
                sendWarningMessage("breaking_blocked", "§c[HB] Не удалось убрать препятствие у "
                        + deferred.pos().toShortString()
                        + ": " + reason
                        + ". Цель не пропущена; строительство поставлено на паузу.");
                clearBreakingState();
                placementFailed = true;
                setState(BuildState.WALKING);
                pauseBuild();
                return;
            }
            sendWarningMessage("breaking_retry", "\u00a7e[HB] \u041d\u0435 \u0443\u0434\u0430\u043b\u043e\u0441\u044c \u0431\u0435\u0437\u043e\u043f\u0430\u0441\u043d\u043e \u0443\u0431\u0440\u0430\u0442\u044c \u043f\u0440\u0435\u043f\u044f\u0442\u0441\u0442\u0432\u0438\u0435 \u0443 "
                    + deferred.pos().toShortString() + ": " + reason
                    + ". \u041f\u043e\u0432\u0442\u043e\u0440\u044f\u044e \u0442\u0443 \u0436\u0435 \u0446\u0435\u043b\u044c (\u043f\u043e\u043f\u044b\u0442\u043a\u0430 " + attempts + ").");
            clearBreakingState();
            placementFailed = true;
            waitThenWalk(Math.min(40, 8 + attempts * 4));
            return;
        }
        currentEntry = null;
        placementFailed = false;
        clearBreakingState();
        setState(BuildState.SORTING);
    }

    private void tickBreaking() {
        if (currentEntry == null || blockToBreak == null || client.world == null) {
            clearBreakingState();
            setState(BuildState.SORTING);
            return;
        }

        BlockState obstruction = client.world.getBlockState(blockToBreak);
        if (obstruction.isReplaceable()) {
            if (!sortMode.isCinematic()) {
                movement.stop();
                camera.stop();
            }
            breakConfirmationTicks++;
            if (breakConfirmationTicks < requiredBreakConfirmationTicks()) return;

            if (resumeParentBreakTarget()) return;
            clearBreakingState();
            if (sortMode.isCinematic() && !clearingQueue.isEmpty()) {
                setState(BuildState.SORTING);
            } else {
                setState(BuildState.WALKING);
            }
            return;
        }
        breakConfirmationTicks = 0;

        // A dependent block may have appeared while the bot was approaching.
        // Recheck before both pathing and the final destruction packet.
        if (!placer.canSafelyBreak(blockToBreak)) {
            if (followProtectedDependent(placer.getLastProtectedDependent())) return;
            skipProtectedObstruction(currentEntry, blockToBreak);
            return;
        }

        if (!placer.canBreakFromCurrentPosition(blockToBreak)) {
            camera.stop();
            if (!movement.isActive()) {
                if (movement.hasRouteFailure()) {
                    String reason = movement.consumeFailureReason();
                    BlockPos routeObstacle = movement.consumeBlockingObstacle();
                    if (followSecondaryBreakTarget(routeObstacle, reason)) return;
                    recordBreakNavigationFailure(reason);
                    return;
                }

                if (!movement.walkToBreak(blockToBreak)) {
                    String reason = movement.consumeFailureReason();
                    BlockPos routeObstacle = movement.consumeBlockingObstacle();
                    if (followSecondaryBreakTarget(routeObstacle, reason)) return;
                    recordBreakNavigationFailure(reason);
                }
            }
            return;
        }

        if (sortMode.isCinematic()) {
            camera.lookAt(placer.getBreakLookTarget(blockToBreak));
            if (isCameraLookingAtTarget(18.0f)) {
                boolean accepted = placer.breakBlock(blockToBreak);
                breakAttempts++;
                breakTicks = 0;
                if (!accepted && breakAttempts >= 5) {
                    deferCurrentBreakTarget("\u0441\u0435\u0440\u0432\u0435\u0440 \u043e\u0442\u043a\u043b\u043e\u043d\u0438\u043b \u0440\u0430\u0437\u0440\u0443\u0448\u0435\u043d\u0438\u0435 " + blockToBreak.toShortString());
                }
            }
            return;
        }

        movement.stop();
        if (breakAttempts >= 5) {
            deferCurrentBreakTarget("сервер не подтвердил разрушение "
                    + blockToBreak.toShortString());
            return;
        }

        camera.lookAt(placer.getBreakLookTarget(blockToBreak));
        breakTicks++;
        if (camera.isConverged() || breakTicks >= 10) {
            camera.stop();
            boolean accepted = placer.breakBlock(blockToBreak);
            breakAttempts++;
            breakTicks = 0;
            if (!accepted && breakAttempts >= 5) {
                deferCurrentBreakTarget("сервер отклонил разрушение "
                        + blockToBreak.toShortString());
            }
        }
    }

    private int requiredBreakConfirmationTicks() {
        int latency = 0;
        if (client.player != null && client.getNetworkHandler() != null) {
            var playerEntry = client.getNetworkHandler().getPlayerListEntry(client.player.getUuid());
            if (playerEntry != null) latency = Math.max(0, playerEntry.getLatency());
        }
        return Math.max(3, Math.min(12, 2 + (latency + 49) / 50));
    }

    private boolean isRemovableObstruction(BlockPos obstruction) {
        if (obstruction == null || client.world == null || currentEntry == null) return false;
        BlockState actual = client.world.getBlockState(obstruction);
        if (actual.isReplaceable()) return false;
        if (!placer.canSafelyBreak(obstruction)) return false;
        if (obstruction.equals(currentEntry.pos())) return true;

        BlockState desired = getAbsoluteBlocks().get(obstruction);
        if (desired == null) return true;
        return !placer.matchesPlacementState(actual, desired)
                && !placer.canCompletePlacementState(actual, desired);
    }

    private boolean isCameraLookingAtTarget(float tolerance) {
        if (client.player == null) return false;
        float yawErr = Math.abs(net.minecraft.util.math.MathHelper.wrapDegrees(camera.getTargetYaw() - client.player.getYaw()));
        float pitchErr = Math.abs(camera.getTargetPitch() - client.player.getPitch());
        return yawErr < tolerance && pitchErr < tolerance;
    }

    public void sendWarningMessage(String key, String message) {
        long now = executorTicks;
        Long last = lastWarningTicks.get(key);
        if (last == null || now - last > 120L) { // 6 seconds cooldown
            sendMessage(message);
            lastWarningTicks.put(key, now);
        }
    }

    public void sendMessage(String message) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal(message), false);
        }
    }

    private record SupportBootstrapResult(BuildEntry entry, boolean deferred) {
        private static final SupportBootstrapResult NONE =
                new SupportBootstrapResult(null, false);
    }

    private record PlacementVerification(BuildEntry entry, long dueTick) {}
}

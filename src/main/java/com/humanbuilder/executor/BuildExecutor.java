package com.humanbuilder.executor;

import com.humanbuilder.HumanBuilderMod;
import com.humanbuilder.camera.CameraSmoother;
import com.humanbuilder.logic.BlockCategory;
import com.humanbuilder.logic.BuildEntry;
import com.humanbuilder.logic.BuildLogicSorter;
import com.humanbuilder.logic.SortMode;
import com.humanbuilder.movement.MovementController;
import com.humanbuilder.placer.BlockPlacer;
import com.humanbuilder.timing.HumanTiming;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
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
    private SortMode sortMode = SortMode.LAYERED;

    /** Флаг, указывающий, что последняя попытка поставить блок провалилась */
    private boolean placementFailed = false;

    /** Счетчик подряд идущих неудачных попыток установки для одного блока */
    private int placementFailureCount = 0;
    private BlockPos lastFailedPos = null;
    private final Map<BlockPos, Integer> navigationFailures = new HashMap<>();
    private final Map<BlockPos, Long> navigationRetryAfter = new HashMap<>();
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

    public SortMode getSortMode() {
        return sortMode;
    }

    public void setSortMode(SortMode sortMode) {
        this.sortMode = sortMode;
        // Если строительство активно, пересчитываем очередь с новым режимом
        if (state != BuildState.IDLE && state != BuildState.PREVIEW) {
            rebuildQueue();
        }
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
        this.timing = timing;
        this.sorter = new BuildLogicSorter();
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
        placementFailed = false;
        placementFailureCount = 0;
        lastFailedPos = null;
        navigationFailures.clear();
        navigationRetryAfter.clear();
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
        savedStateBeforePause = state;
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
                case LOOKING  -> tickLooking();
                case PLACING  -> tickPlacing();
                case WAITING  -> tickWaiting();
                case MISTAKE  -> tickMistake();
                default       -> { return; }
            }

            if (handledState == BuildState.PLACING
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
                list.add(new BuildEntry(entry.pos().add(schematicOrigin), entry.state(), entry.category()));
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
                                new BuildEntry(new BlockPos(x, y, z), relative.state(), relative.category()),
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
                waitTarget = 1;
                setState(BuildState.WAITING);
                return;
            }
            // Очередь пуста — строительство завершено
            sendMessage("§a[HB] ✓ Постройка завершена! Блоков: " + blocksPlaced);
            movement.reset();
            camera.stop();
            setState(BuildState.IDLE);
            return;
        }

        if (state == BuildState.BREAKING) return;

        // Определяем задержку при смене категории
        if (lastCategory != null && currentEntry.category() != lastCategory) {
            if (currentEntry.category().getPriority() > lastCategory.getPriority()) {
                // Смена категории (напр., стены → крыша): длинная пауза
                sendMessage("§7[HB] " + currentEntry.category().getDisplayName() + "...");
                waitTarget = timing.floorTransitionDelay();
                resumeWalkingAfterWait = true;
                setState(BuildState.WAITING);
                return;
            }
        }

        setState(BuildState.WALKING);
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

        // Если ходьба была инициирована из-за неуспешной установки блока
        if (placementFailed) {
            if (movement.isWithinReach(currentEntry.pos())
                    && placer.canPlaceFromCurrentPosition(currentEntry.pos(), currentEntry.state())) {
                movement.stop();
                placementFailed = false;
                setState(BuildState.LOOKING);
            } else if (!movement.isActive()) {
                beginWalking(true);
            }
            return;
        }

        // Distance alone is insufficient: a wall may hide every support face.
        if (movement.isWithinReach(currentEntry.pos())
                && placer.canPlaceFromCurrentPosition(currentEntry.pos(), currentEntry.state())) {
            movement.stop();
            setState(BuildState.LOOKING);
            return;
        }

        // Если движение ещё не начато — запускаем
        if (!movement.isActive()) {
            beginWalking(false);
        }
    }

    private void tickLooking() {
        if (currentEntry == null) {
            setState(BuildState.SORTING);
            return;
        }

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
            waitTarget = 1;
            resumeLookingAfterWait = true;
            setState(BuildState.WAITING);
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

        // Проверяем, действительно ли нужный блок сейчас в руке
        if (!placer.isReady(currentEntry.state())) {
            setState(BuildState.LOOKING);
            return;
        }

        // Пробуем поставить
        boolean success = placer.placeBlock(currentEntry.pos(), currentEntry.state());

        if (success) {
            schedulePlacementVerification(currentEntry);
            if (placer.requiresPlacementVerification(currentEntry.state())) {
                // Directional and half-height blocks are counted only after the
                // client world confirms their exact state. DOUBLE slabs return
                // here once more to receive their second legitimate click.
                addFirstQueuedEntry(currentEntry);
            } else {
                blocksPlaced++;
                markEntryComplete(currentEntry);
            }
            lastCategory = currentEntry.category();
            placementFailed = false;
            placementFailureCount = 0;
            lastFailedPos = null;
            navigationFailures.remove(currentEntry.pos());

            // Определяем задержку
            if (timing.shouldMakeMistake()) {
                // Имитация ошибки!
                waitTarget = timing.mistakeRecoveryDuration();
                setState(BuildState.MISTAKE);
            } else if (timing.shouldPause()) {
                // Микро-пауза «задумался»
                waitTarget = timing.pauseDuration();
                setState(BuildState.WAITING);
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

            // Если не удалось поставить блок 3 раза подряд с разных точек обхода
            if (placementFailureCount >= 3) {
                sendMessage("§e[HB] ⚠ Не удалось установить блок на " + currentPos.toShortString() + " после 3 попыток. Откладываю в конец очереди.");
                offerQueuedEntry(currentEntry);
                currentEntry = null;
                placementFailed = false;
                placementFailureCount = 0;
                lastFailedPos = null;
                setState(BuildState.SORTING);
                return;
            }

            // Не удалось поставить — пробуем переместиться
            sendMessage("§7[HB] Не могу поставить блок, перемещаюсь...");
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

            if (resumeLookingAfterWait) {
                resumeLookingAfterWait = false;
                setState(BuildState.LOOKING);
            } else if (resumeWalkingAfterWait) {
                resumeWalkingAfterWait = false;
                setState(BuildState.WALKING);
            } else {
                // Переходим к следующему блоку
                setState(BuildState.SORTING);
            }
        }
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
            setState(BuildState.SORTING);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  Утилиты
    // ════════════════════════════════════════════════════════════════════

    /**
     * Проверяет, есть ли у блока опора (хотя бы один твёрдый сосед по 6 направлениям).
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

    private boolean handleOccupiedCurrentTarget() {
        if (currentEntry == null || client.world == null) return false;

        BlockState actual = client.world.getBlockState(currentEntry.pos());
        if (actual.isReplaceable()) return false;
        if (placer.canCompletePlacementState(actual, currentEntry.state())) return false;

        if (placer.matchesPlacementState(actual, currentEntry.state())) {
            movement.stop();
            camera.stop();
            if (pendingEntries.containsKey(currentEntry.pos())) {
                blocksPlaced++;
                markEntryComplete(currentEntry);
            }
            lastCategory = currentEntry.category();
            navigationFailures.remove(currentEntry.pos());
            currentEntry = null;
            setState(BuildState.SORTING);
            return true;
        }

        beginBreaking(currentEntry, currentEntry.pos());
        return true;
    }

    private void handleNavigationFailure(String reason) {
        if (currentEntry == null) {
            setState(BuildState.SORTING);
            return;
        }

        BlockPos failedPos = currentEntry.pos();
        BlockPos obstruction = placer.consumePlacementObstruction();
        BlockPos routeObstruction = movement.consumeBlockingObstacle();
        if (!isRemovableObstruction(obstruction)) obstruction = routeObstruction;
        if (isRemovableObstruction(obstruction)) {
            beginBreaking(currentEntry, obstruction);
            return;
        }
        int attempts = navigationFailures.merge(failedPos, 1, Integer::sum);
        movement.stop();
        navigationRetryAfter.put(failedPos,
                executorTicks + navigationCooldownTicks(attempts));

        if (attempts >= 3) {
            sendMessage("§e[HB] Временно пропускаю " + failedPos.toShortString()
                    + ": " + reason + ". Вернусь к блоку позже.");
        } else {
            sendMessage("§7[HB] Меняю подход к " + failedPos.toShortString()
                    + " (попытка " + attempts + "): " + reason + ".");
        }
        offerQueuedEntry(currentEntry);
        currentEntry = null;
        placementFailed = false;
        setState(BuildState.SORTING);
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
        return advanceToNextBlock(true);
    }

    private boolean advanceToNextBlock(boolean allowBatch) {
        if (!hasQueuedEntries()) {
            currentEntry = null;
            return false;
        }

        Integer queueLayer = usesLayerLock() ? layerQueues.firstKey() : null;
        Deque<BuildEntry> queue = queueLayer == null ? buildQueue : layerQueues.get(queueLayer);
        int maxChecks = queue.size();
        int checks = 0;
        boolean sawCoolingDownEntry = false;

        while (!queue.isEmpty() && checks < maxChecks) {
            BuildEntry entry = queue.poll();
            checks++;

            if (!activeBatch.isEmpty() && !activeBatch.contains(entry.pos())) {
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

            if (allowBatch && activeBatch.isEmpty()) {
                beginAdjacentBatch(entry);
            }

            // Пропускаем, если на этой позиции уже стоит нужный блок или любой незаменяемый
            if (client.world != null) {
                BlockState worldState = client.world.getBlockState(entry.pos());
                if (worldState.getBlock() == entry.state().getBlock()) {
                    if (!placer.matchesPlacementState(worldState, entry.state())) {
                        if (placer.canCompletePlacementState(worldState, entry.state())) {
                            currentEntry = entry;
                            hotbarSyncAttempts = 0;
                            finishQueueAccess(queueLayer, queue);
                            return true;
                        }
                        finishQueueAccess(queueLayer, queue);
                        beginBreaking(entry, entry.pos());
                        return true;
                    }
                    blocksPlaced++;
                    markEntryComplete(entry);
                    continue;
                }
                if (!worldState.isReplaceable()) {
                    finishQueueAccess(queueLayer, queue);
                    beginBreaking(entry, entry.pos());
                    return true;
                }
            }

            // Проверяем опору: есть ли хотя бы один твёрдый блок-сосед?
            if (!hasSupport(entry.pos())) {
                // Нет опоры — откладываем в конец очереди
                queue.offer(entry);
                continue;
            }

            currentEntry = entry;
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

        if (sawCoolingDownEntry && hasQueuedEntries()) {
            waitTarget = 5;
            setState(BuildState.WAITING);
            return false;
        }

        // Если прошли полный круг и ни один блок не получил опору
        if (hasQueuedEntries()) {
            sendMessage("§c[HB] ⚠ Невозможно продолжить: оставшиеся " + pendingEntries.size() + " блоков висят в воздухе без опоры!");
            sendMessage("§7[HB] Попробуйте подставить блоки вручную или сместить голограмму.");
            pauseBuild();
            return false;
        }

        currentEntry = null;
        return false;
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
        activeBatch.clear();
        hotbarSyncAttempts = 0;

        if (state != BuildState.PAUSED) {
            setState(BuildState.SORTING);
        } else {
            savedStateBeforePause = BuildState.SORTING;
        }
    }

    private void setState(BuildState newState) {
        if (this.state != newState) {
            HumanBuilderMod.LOGGER.debug("[HumanBuilder] State {} -> {} (target={})",
                    this.state, newState,
                    currentEntry == null ? "none" : currentEntry.pos().toShortString());
        }
        this.state = newState;
        this.waitTicks = 0;
    }

    private void updateDiagnostics() {
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
            diagnosticTargetTicks = 0;
            movement.failActiveRoute("watchdog: маршрут слишком долго не завершался");
            return;
        }
        if (diagnosticTargetTicks >= 200 && diagnosticTargetTicks % 200 == 0) {
            HumanBuilderMod.LOGGER.warn(
                    "[HumanBuilder] No completed progress for {} ticks: state={}, target={}, movementActive={}",
                    diagnosticTargetTicks, state, target.toShortString(), movement.isActive());
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
                    || !activeBatch.add(pos)) continue;
            open.addLast(pos.east());
            open.addLast(pos.west());
            open.addLast(pos.south());
            open.addLast(pos.north());
        }
    }

    private void initializeBuildQueues(Collection<BuildEntry> entries) {
        buildQueue = new ArrayDeque<>();
        layerQueues.clear();
        placementVerifications.clear();
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

    private boolean hasQueuedEntries() {
        return usesLayerLock() ? !layerQueues.isEmpty() : !buildQueue.isEmpty();
    }

    private void offerQueuedEntry(BuildEntry entry) {
        if (usesLayerLock()) {
            layerQueues.computeIfAbsent(entry.pos().getY(), ignored -> new ArrayDeque<>()).offer(entry);
        } else {
            buildQueue.offer(entry);
        }
    }

    private void addFirstQueuedEntry(BuildEntry entry) {
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

    private void markEntryComplete(BuildEntry entry) {
        if (pendingEntries.remove(entry.pos()) == null) return;
        navigationFailures.remove(entry.pos());
        navigationRetryAfter.remove(entry.pos());
        long chunk = chunkKey(entry.pos().getX() >> 4, entry.pos().getZ() >> 4);
        Map<BlockPos, BuildEntry> chunkEntries = pendingEntriesByChunk.get(chunk);
        if (chunkEntries != null) {
            chunkEntries.remove(entry.pos());
            if (chunkEntries.isEmpty()) pendingEntriesByChunk.remove(chunk);
        }
        activeBatch.remove(entry.pos());
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
        return Math.max(10, Math.min(60, 10 + (latency + 24) / 25));
    }

    private boolean usesLayerLock() {
        return sortMode == SortMode.LAYERED || sortMode == SortMode.MIXED;
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

    private void beginBreaking(BuildEntry entry, BlockPos obstruction) {
        movement.stop();
        camera.stop();
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
        movement.stop();
        camera.stop();
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
            navigationRetryAfter.put(deferred.pos(),
                    executorTicks + navigationCooldownTicks(attempts));
            offerQueuedEntry(deferred);
            sendMessage("§e[HB] Не удалось безопасно убрать препятствие у "
                    + deferred.pos().toShortString() + ": " + reason
                    + ". Вернусь к нему позже.");
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
            movement.stop();
            camera.stop();
            breakConfirmationTicks++;
            if (breakConfirmationTicks < requiredBreakConfirmationTicks()) return;

            if (resumeParentBreakTarget()) return;
            clearBreakingState();
            setState(BuildState.WALKING);
            return;
        }
        breakConfirmationTicks = 0;

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
        if (obstruction.equals(currentEntry.pos())) return true;

        BlockState desired = getAbsoluteBlocks().get(obstruction);
        if (desired == null) return true;
        return !placer.matchesPlacementState(actual, desired)
                && !placer.canCompletePlacementState(actual, desired);
    }

    public void sendMessage(String message) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal(message), false);
        }
    }

    private record PlacementVerification(BuildEntry entry, long dueTick) {}
}

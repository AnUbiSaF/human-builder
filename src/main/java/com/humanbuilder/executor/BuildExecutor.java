package com.humanbuilder.executor;

import com.humanbuilder.camera.CameraSmoother;
import com.humanbuilder.logic.BlockCategory;
import com.humanbuilder.logic.BuildEntry;
import com.humanbuilder.logic.BuildLogicSorter;
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
 * Каждый тик вызывается tick(), который обрабатывает текущее состояние
 * и при необходимости переходит к следующему.
 */
public class BuildExecutor {

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

    /** Статистика */
    private int blocksPlaced = 0;
    private int totalBlocks = 0;

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
     * Начать строительство из отсортированной карты блоков.
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

        // Сортируем блоки
        List<BuildEntry> sorted = sorter.sort(blocks, client.player.getBlockPos());

        buildQueue = new ArrayDeque<>(sorted);
        totalBlocks = sorted.size();
        blocksPlaced = 0;
        lastCategory = null;
        currentEntry = null;
        resumeLookingAfterWait = false;

        setState(BuildState.SORTING);
        sendMessage("§a[HB] Строительство начато! Блоков: " + totalBlocks);
    }

    /**
     * Остановить строительство.
     */
    public void stopBuild() {
        setState(BuildState.IDLE);
        camera.stop();
        movement.stop();
        buildQueue.clear();
        currentEntry = null;
        resumeLookingAfterWait = false;
        sendMessage("§e[HB] Строительство остановлено. Поставлено: " + blocksPlaced + "/" + totalBlocks);
    }

    /**
     * Главный тик — вызывается из TickHandler каждый клиентский тик.
     */
    public void tick() {
        if (state == BuildState.IDLE) return;
        if (client.player == null) return;

        switch (state) {
            case SORTING  -> tickSorting();
            case WALKING  -> tickWalking();
            case LOOKING  -> tickLooking();
            case PLACING  -> tickPlacing();
            case WAITING  -> tickWaiting();
            case MISTAKE  -> tickMistake();
            default       -> {} // SCAFFOLDING — TODO
        }
    }

    public BuildState getState() { return state; }
    public int getBlocksPlaced() { return blocksPlaced; }
    public int getTotalBlocks()  { return totalBlocks; }
    public boolean isActive()    { return state != BuildState.IDLE; }

    // ════════════════════════════════════════════════════════════════════
    //  Обработчики состояний
    // ════════════════════════════════════════════════════════════════════

    /**
     * SORTING: берём следующий блок из очереди и идём к нему.
     */
    private void tickSorting() {
        if (!advanceToNextBlock()) {
            // Очередь пуста — строительство завершено
            sendMessage("§a[HB] ✓ Постройка завершена! Блоков: " + blocksPlaced);
            setState(BuildState.IDLE);
            return;
        }

        // Определяем задержку при смене категории
        if (lastCategory != null && currentEntry.category() != lastCategory) {
            if (currentEntry.category().getPriority() > lastCategory.getPriority()) {
                // Смена категории (напр., стены → крыша): длинная пауза
                sendMessage("§7[HB] " + currentEntry.category().getDisplayName() + "...");
                waitTarget = timing.floorTransitionDelay();
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

        // Если уже в пределах досягаемости — переходим к наведению
        if (movement.isWithinReach(currentEntry.pos())) {
            movement.stop();
            setState(BuildState.LOOKING);
            return;
        }

        // Если движение ещё не начато — запускаем
        if (!movement.isActive()) {
            movement.walkTo(currentEntry.pos());
        }

        // Движение обрабатывается в movement.tick() (вызывается из TickHandler)
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
            placer.switchToBlock(currentEntry.state());
            waitTarget = 3; // задержка в 3 тика (150 мс) на синхронизацию
            resumeLookingAfterWait = true;
            setState(BuildState.WAITING);
            return;
        }

        // Получаем точку, куда нужно смотреть (центр грани опорного блока)
        Vec3d lookTarget = placer.getPlacementLookTarget(currentEntry.pos());

        // Обновляем цель взгляда камеры каждый тик, компенсируя движение игрока
        camera.lookAt(lookTarget);

        // Ждём конвергенции прицела
        if (camera.isConverged()) {
            camera.stop();
            setState(BuildState.PLACING);
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

        // Проверяем, действительно ли нужный блок сейчас в руке
        if (!placer.isReady(currentEntry.state())) {
            setState(BuildState.LOOKING);
            return;
        }

        // Пробуем поставить
        boolean success = placer.placeBlock(currentEntry.pos(), currentEntry.state());

        if (success) {
            blocksPlaced++;
            lastCategory = currentEntry.category();

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
                // Обычная задержка между блоками
                waitTarget = timing.blockPlaceDelay();
                setState(BuildState.WAITING);
            }
        } else {
            // Не удалось поставить — пробуем переместиться
            sendMessage("§7[HB] Не могу поставить блок, перемещаюсь...");
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
     * Берёт следующий блок из очереди. Пропускает уже установленные блоки.
     */
    private boolean advanceToNextBlock() {
        while (!buildQueue.isEmpty()) {
            BuildEntry entry = buildQueue.poll();

            // Пропускаем, если на этой позиции уже стоит незаменяемый блок
            if (client.world != null) {
                BlockState worldState = client.world.getBlockState(entry.pos());
                if (!worldState.isReplaceable()) {
                    blocksPlaced++; // считаем как уже поставленный
                    continue;
                }
            }

            currentEntry = entry;
            return true;
        }

        currentEntry = null;
        return false;
    }

    private void setState(BuildState newState) {
        if (this.state != newState) {
            sendMessage("§7[HB-State] " + this.state.name() + " -> " + newState.name());
        }
        this.state = newState;
        this.waitTicks = 0;
    }

    private void sendMessage(String message) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal(message), false);
        }
    }
}

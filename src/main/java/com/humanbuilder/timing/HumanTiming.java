package com.humanbuilder.timing;

import java.util.Random;

/**
 * Генератор человекоподобных задержек и случайных событий.
 *
 * Все задержки возвращаются в тиках (1 тик = 50 мс).
 * Диапазоны подобраны так, чтобы при записи от первого лица
 * действия игрока выглядели естественно.
 */
public class HumanTiming {

    private final Random random = new Random();

    // ════════════════════════════════════════════════════════════════════
    // ── Задержки между действиями ────────────────────────────────════
    // Все задержки возвращаются в тиках (1 тик = 50 мс)

    /** Между блоками в одной стене: 50–100 мс */
    public int blockPlaceDelay() {
        return randomRange(1, 2);
    }

    /** Переход к новой стене / секции: 150–250 мс */
    public int wallTransitionDelay() {
        return randomRange(3, 5);
    }

    /** Переход на новый этаж / приоритет: 250–500 мс */
    public int floorTransitionDelay() {
        return randomRange(5, 10);
    }

    /** Задержка после переключения слота хотбара: 50 мс */
    public int hotbarSwitchDelay() {
        return 1;
    }

    // ── Случайные события (шанс за тик проверки) ─────────────────════

    /** Микро-пауза «задумался»: 1% шанс на каждый блок */
    public boolean shouldPause() {
        return random.nextFloat() < 0.01f;
    }

    /** Длительность микро-паузы: 250–500 мс */
    public int pauseDuration() {
        return randomRange(5, 10);
    }

    /** «Ошибка» — поставил не тот блок: 0.5% шанс */
    public boolean shouldMakeMistake() {
        return random.nextFloat() < 0.005f;
    }

    /** Время на осознание ошибки + исправление: 250–500 мс */
    public int mistakeRecoveryDuration() {
        return randomRange(5, 10);
    }

    /** Случайное оглядывание (повернуть камеру по сторонам): 0.5% шанс */
    public boolean shouldLookAround() {
        return random.nextFloat() < 0.005f;
    }

    /** Длительность оглядывания: 250–500 мс */
    public int lookAroundDuration() {
        return randomRange(5, 10);
    }

    // ════════════════════════════════════════════════════════════════════
    //  Утилиты
    // ════════════════════════════════════════════════════════════════════

    /**
     * Случайное целое в диапазоне [min, max] включительно.
     */
    public int randomRange(int min, int max) {
        if (min >= max) return min;
        return min + random.nextInt(max - min + 1);
    }

    /**
     * Гауссово (нормальное) распределение вокруг среднего.
     * Полезно для более «человечных» задержек, которые группируются
     * вокруг центра диапазона с редкими выбросами.
     */
    public int gaussianRange(int mean, int stdDev) {
        return Math.max(1, (int) (mean + random.nextGaussian() * stdDev));
    }
}

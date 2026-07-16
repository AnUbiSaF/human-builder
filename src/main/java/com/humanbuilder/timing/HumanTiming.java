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
    //  Задержки между действиями
    // ════════════════════════════════════════════════════════════════════

    /** Между блоками в одной стене: 150–350 мс */
    public int blockPlaceDelay() {
        return randomRange(3, 7);
    }

    /** Переход к новой стене / секции: 500–1000 мс */
    public int wallTransitionDelay() {
        return randomRange(10, 20);
    }

    /** Переход на новый этаж / приоритет: 1000–2000 мс */
    public int floorTransitionDelay() {
        return randomRange(20, 40);
    }

    /** Задержка после переключения слота хотбара: 100–200 мс */
    public int hotbarSwitchDelay() {
        return randomRange(2, 4);
    }

    // ════════════════════════════════════════════════════════════════════
    //  Случайные события (шанс за тик проверки)
    // ════════════════════════════════════════════════════════════════════

    /** Микро-пауза «задумался»: 5% шанс на каждый блок */
    public boolean shouldPause() {
        return random.nextFloat() < 0.05f;
    }

    /** Длительность микро-паузы: 750–1500 мс */
    public int pauseDuration() {
        return randomRange(15, 30);
    }

    /** «Ошибка» — поставил не тот блок: 1.5% шанс */
    public boolean shouldMakeMistake() {
        return random.nextFloat() < 0.015f;
    }

    /** Время на осознание ошибки + исправление: 1000–2000 мс */
    public int mistakeRecoveryDuration() {
        return randomRange(20, 40);
    }

    /** Случайное оглядывание (повернуть камеру по сторонам): 2% шанс */
    public boolean shouldLookAround() {
        return random.nextFloat() < 0.02f;
    }

    /** Длительность оглядывания: 600–1200 мс */
    public int lookAroundDuration() {
        return randomRange(12, 24);
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

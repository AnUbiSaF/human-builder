package com.humanbuilder.timing;

import java.util.Random;

/**
 * Генератор задержек между действиями.
 *
 * Все задержки возвращаются в тиках (1 тик = 50 мс).
 * Минимальные задержки для максимальной скорости строительства.
 */
public class HumanTiming {

    private final Random random = new Random();

    // ════════════════════════════════════════════════════════════════════
    // ── Задержки между действиями ────────────────────────────────════
    // Все задержки возвращаются в тиках (1 тик = 50 мс)

    /** Между блоками: 1 тик (50 мс) */
    public int blockPlaceDelay() {
        return 1;
    }

    /** Переход к новой стене / секции: 2 тика (100 мс) */
    public int wallTransitionDelay() {
        return 2;
    }

    /** Переход на новый этаж / приоритет: 1 тик (50 мс) */
    public int floorTransitionDelay() {
        return 1;
    }

    /** Задержка после переключения слота хотбара: 1 тик */
    public int hotbarSwitchDelay() {
        return 1;
    }

    // ── Случайные события: ОТКЛЮЧЕНЫ для максимальной скорости ────════

    /** Микро-пауза — отключена */
    public boolean shouldPause() {
        return false;
    }

    /** Длительность микро-паузы */
    public int pauseDuration() {
        return 0;
    }

    /** «Ошибка» — отключена */
    public boolean shouldMakeMistake() {
        return false;
    }

    /** Время на осознание ошибки */
    public int mistakeRecoveryDuration() {
        return 0;
    }

    /** Случайное оглядывание — отключено */
    public boolean shouldLookAround() {
        return false;
    }

    /** Длительность оглядывания */
    public int lookAroundDuration() {
        return 0;
    }

    // ════════════════════════════════════════════════════════════════════
    //  Утилиты
    // ════════════════════════════════════════════════════════════════════

    public int randomRange(int min, int max) {
        if (min >= max) return min;
        return min + random.nextInt(max - min + 1);
    }

    public int gaussianRange(int mean, int stdDev) {
        return Math.max(1, (int) (mean + random.nextGaussian() * stdDev));
    }
}

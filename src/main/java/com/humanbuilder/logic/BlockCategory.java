package com.humanbuilder.logic;

/**
 * Категории блоков для логической сортировки.
 *
 * Приоритет определяет порядок строительства:
 * фундамент → стены → перегородки → полы → крыша → декор.
 */
public enum BlockCategory {

    /** Нижний слой постройки (Y == minY) */
    FOUNDATION(0, "Фундамент"),

    /** Внешние стены (на границе X/Z структуры) */
    WALL(1, "Стены"),

    /** Внутренние перегородки (между комнатами) */
    INTERIOR_WALL(2, "Перегородки"),

    /** Заполнение пола, лестницы между этажами */
    FLOOR(3, "Полы"),

    /** Потолок / крыша (Y == maxY или slab/stairs) */
    CEILING(4, "Крыша"),

    /** Декоративные элементы: факелы, стёкла, двери, ковры */
    DECOR(5, "Декор");

    private final int priority;
    private final String displayName;

    BlockCategory(int priority, String displayName) {
        this.priority = priority;
        this.displayName = displayName;
    }

    public int getPriority()      { return priority; }
    public String getDisplayName() { return displayName; }
}

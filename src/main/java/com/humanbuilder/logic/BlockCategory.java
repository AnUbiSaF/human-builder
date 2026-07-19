package com.humanbuilder.logic;

/** Logical role used for ordering, timing and hologram colors. */
public enum BlockCategory {
    FOUNDATION(0, "Основание слоя"),
    PILLAR(1, "Контур"),
    WALL(2, "Основной каркас"),
    ROOF(3, "Крыша"),
    WINDOW(4, "Внешнее заполнение"),
    INTERIOR_WALL(5, "Внутренние конструкции"),
    DECOR(6, "Внутренние детали");

    private final int priority;
    private final String displayName;

    BlockCategory(int priority, String displayName) {
        this.priority = priority;
        this.displayName = displayName;
    }

    public int getPriority() {
        return priority;
    }

    public String getDisplayName() {
        return displayName;
    }
}

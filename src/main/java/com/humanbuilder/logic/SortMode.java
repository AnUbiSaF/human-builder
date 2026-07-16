package com.humanbuilder.logic;

/**
 * Режимы сортировки блоков схемы.
 */
public enum SortMode {
    /** Классический режим по категориям (сначала фундамент, затем стены, затем полы, и т.д. по всей высоте) */
    DEFAULT("Классический"),

    /** Смешанный режим по слоям (первый слой целиком, затем стены всех этажей, затем заполнение) */
    MIXED("Смешанный"),

    /** Строгий режим: полностью завершает каждый Y-слой перед переходом выше. */
    LAYERED("По слоям"),

    /** Последовательные рабочие серии с учетом опор и состояния блоков. */
    REALISTIC("Реалистичный");

    private final String displayName;

    SortMode(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}

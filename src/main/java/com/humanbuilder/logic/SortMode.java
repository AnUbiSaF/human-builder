package com.humanbuilder.logic;

/** Available autonomous construction orders. */
public enum SortMode {
    LAYERED("По слоям"),
    ARCHITECTURAL("Архитектурный таймлапс");

    private final String displayName;

    SortMode(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isCinematic() {
        return this == ARCHITECTURAL;
    }
}

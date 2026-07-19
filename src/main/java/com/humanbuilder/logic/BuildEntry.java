package com.humanbuilder.logic;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

/**
 * Одна запись в очереди строительства.
 * Связывает позицию блока, его состояние и логическую категорию.
 *
 * @param pos       координаты в мире, куда нужно поставить блок
 * @param state     тип и состояние блока (включая rotation, facing и т.д.)
 * @param category  логическая категория (фундамент, стена, крыша…)
 * @param workGroup фасадный участок, который executor завершает атомарно
 * @param temporary служебная опора вне схемы, не входящая в прогресс
 */
public record BuildEntry(
        BlockPos pos,
        BlockState state,
        BlockCategory category,
        int workGroup,
        boolean temporary
) {
    public BuildEntry(BlockPos pos, BlockState state, BlockCategory category) {
        this(pos, state, category, 0, false);
    }

    public BuildEntry(BlockPos pos, BlockState state, BlockCategory category, int workGroup) {
        this(pos, state, category, workGroup, false);
    }

    /**
     * Расстояние (Manhattan) до другой позиции.
     * Используется для nearest-neighbor сортировки.
     */
    public int manhattanDistance(BlockPos other) {
        return Math.abs(pos.getX() - other.getX())
             + Math.abs(pos.getY() - other.getY())
             + Math.abs(pos.getZ() - other.getZ());
    }

    /**
     * Квадрат евклидова расстояния (без sqrt, для сравнения).
     */
    public double squaredDistance(BlockPos other) {
        double dx = pos.getX() - other.getX();
        double dy = pos.getY() - other.getY();
        double dz = pos.getZ() - other.getZ();
        return dx * dx + dy * dy + dz * dz;
    }
}

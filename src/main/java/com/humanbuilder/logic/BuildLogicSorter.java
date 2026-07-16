package com.humanbuilder.logic;

import net.minecraft.block.*;
import net.minecraft.util.math.BlockPos;

import java.util.*;

/**
 * Сортировщик блоков для «человеческого» порядка строительства.
 *
 * Принимает «сырой» набор блоков из парсера и выстраивает их
 * в очередь, которая выглядит логично:
 *
 *   1. Фундамент (нижний слой, по спирали от угла)
 *   2. Стены (по линиям — идём вдоль стены, ставим ряд)
 *   3. Перегородки, полы
 *   4. Крыша / потолок
 *   5. Декор (факелы, двери, стёкла)
 *
 * Внутри каждой категории используется Nearest-Neighbor +
 * Wall-Following: бот ставит ближайший блок, а затем «ведёт»
 * линию, если следующий блок — сосед по X или Z.
 */
public class BuildLogicSorter {

    /**
     * Главный метод: сортирует блоки в порядке строительства.
     *
     * @param blocks    карта позиций и состояний из SchematicParser
     * @param playerPos текущая позиция игрока (для nearest-neighbor)
     * @return упорядоченная очередь строительства
     */
    public List<BuildEntry> sort(Map<BlockPos, BlockState> blocks, BlockPos playerPos) {
        if (blocks.isEmpty()) return Collections.emptyList();

        // ── 1. Находим bounding box ───────────────────────────────────
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;

        for (BlockPos pos : blocks.keySet()) {
            minX = Math.min(minX, pos.getX()); maxX = Math.max(maxX, pos.getX());
            minY = Math.min(minY, pos.getY()); maxY = Math.max(maxY, pos.getY());
            minZ = Math.min(minZ, pos.getZ()); maxZ = Math.max(maxZ, pos.getZ());
        }

        // ── 2. Классифицируем каждый блок ─────────────────────────────
        Map<BlockCategory, List<BuildEntry>> categorized = new EnumMap<>(BlockCategory.class);

        for (var entry : blocks.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState state = entry.getValue();
            BlockCategory category = classify(pos, state, minX, maxX, minY, maxY, minZ, maxZ, blocks);

            categorized.computeIfAbsent(category, k -> new ArrayList<>())
                        .add(new BuildEntry(pos, state, category));
        }

        // ── 3. Сортируем внутри каждой категории ──────────────────────
        List<BuildEntry> result = new ArrayList<>();
        BlockPos currentPos = playerPos;

        for (BlockCategory category : BlockCategory.values()) {
            List<BuildEntry> entries = categorized.getOrDefault(category, Collections.emptyList());
            if (entries.isEmpty()) continue;

            // Для стен и фундамента — wall-following.
            // Для остальных — nearest-neighbor по этажам.
            List<BuildEntry> sorted;
            if (category == BlockCategory.FOUNDATION || category == BlockCategory.WALL) {
                sorted = wallFollowingSort(entries, currentPos);
            } else {
                sorted = layerByLayerSort(entries, currentPos);
            }

            result.addAll(sorted);

            if (!sorted.isEmpty()) {
                currentPos = sorted.get(sorted.size() - 1).pos();
            }
        }

        return result;
    }

    // ════════════════════════════════════════════════════════════════════
    //  Классификация блоков
    // ════════════════════════════════════════════════════════════════════

    private BlockCategory classify(
            BlockPos pos, BlockState state,
            int minX, int maxX, int minY, int maxY, int minZ, int maxZ,
            Map<BlockPos, BlockState> allBlocks
    ) {
        // Декор — сначала, чтобы не перепутать с другими категориями
        if (isDecorBlock(state)) {
            return BlockCategory.DECOR;
        }

        // Фундамент — нижний слой
        if (pos.getY() == minY) {
            return BlockCategory.FOUNDATION;
        }

        // Крыша / потолок — верхний слой или кровельные блоки
        if (pos.getY() == maxY || isRoofBlock(state)) {
            return BlockCategory.CEILING;
        }

        // Внешняя стена — блок на границе X или Z
        if (pos.getX() == minX || pos.getX() == maxX
         || pos.getZ() == minZ || pos.getZ() == maxZ) {
            return BlockCategory.WALL;
        }

        // Внутренняя перегородка — имеет хотя бы одного воздушного соседа
        if (hasAirNeighborHorizontal(pos, allBlocks)) {
            return BlockCategory.INTERIOR_WALL;
        }

        // Всё остальное — заполнение пола
        return BlockCategory.FLOOR;
    }

    /**
     * Проверяет, является ли блок «декоративным» (ставится в последнюю очередь).
     */
    private boolean isDecorBlock(BlockState state) {
        Block block = state.getBlock();
        return block instanceof TorchBlock
            || block instanceof WallTorchBlock
            || block instanceof CarpetBlock
            || block instanceof FlowerPotBlock
            || block instanceof SignBlock
            || block instanceof WallSignBlock
            || block instanceof LanternBlock
            || block instanceof ChainBlock
            || block instanceof PaneBlock       // стеклянные панели
            || block instanceof DoorBlock
            || block instanceof TrapdoorBlock
            || block instanceof ButtonBlock
            || block instanceof LeverBlock
            || block instanceof PressurePlateBlock
            || block instanceof BannerBlock
            || block instanceof WallBannerBlock
            || block instanceof FlowerBlock
            || block instanceof LadderBlock;
    }

    /**
     * Проверяет, является ли блок «кровельным».
     */
    private boolean isRoofBlock(BlockState state) {
        Block block = state.getBlock();
        return block instanceof StairsBlock
            || block instanceof SlabBlock;
    }

    /**
     * Есть ли у блока горизонтальный сосед, которого нет в схеме?
     * Если да — это «стена» (граничит с пустотой).
     */
    private boolean hasAirNeighborHorizontal(BlockPos pos, Map<BlockPos, BlockState> blocks) {
        return !blocks.containsKey(pos.north())
            || !blocks.containsKey(pos.south())
            || !blocks.containsKey(pos.east())
            || !blocks.containsKey(pos.west());
    }

    // ════════════════════════════════════════════════════════════════════
    //  Алгоритмы сортировки
    // ════════════════════════════════════════════════════════════════════

    /**
     * Wall-Following: ведём «кладку» вдоль стены.
     *
     * 1. Находим ближайший блок к текущей позиции.
     * 2. От него ищем соседа (±1 по X или Z на том же Y).
     * 3. Если сосед есть — добавляем его (эффект «кладки ряда»).
     * 4. Если соседей нет — прыгаем к ближайшему неиспользованному.
     */
    private List<BuildEntry> wallFollowingSort(List<BuildEntry> entries, BlockPos start) {
        List<BuildEntry> sorted = new ArrayList<>(entries.size());
        Set<BlockPos> remaining = new LinkedHashSet<>();
        Map<BlockPos, BuildEntry> lookup = new HashMap<>();

        for (BuildEntry e : entries) {
            remaining.add(e.pos());
            lookup.put(e.pos(), e);
        }

        BlockPos current = start;

        while (!remaining.isEmpty()) {
            // Найти ближайший из оставшихся
            BlockPos nearest = findNearest(current, remaining);
            if (nearest == null) break;

            // «Вести» линию от этого блока
            BlockPos linePos = nearest;
            while (linePos != null && remaining.contains(linePos)) {
                remaining.remove(linePos);
                sorted.add(lookup.get(linePos));
                current = linePos;

                // Ищем соседа по линии (предпочитаем то же направление)
                linePos = findWallNeighbor(linePos, remaining);
            }
        }

        return sorted;
    }

    /**
     * Ищет горизонтального соседа (±1 по X или Z, тот же Y).
     */
    private BlockPos findWallNeighbor(BlockPos pos, Set<BlockPos> remaining) {
        // Приоритет: X+, X-, Z+, Z-
        BlockPos[] neighbors = {
            pos.east(), pos.west(), pos.south(), pos.north()
        };
        for (BlockPos n : neighbors) {
            if (remaining.contains(n)) return n;
        }

        // Попробовать на тот же Y, но через 1 блок по Y (ряд выше)
        BlockPos up = pos.up();
        BlockPos[] neighborsUp = {
            up.east(), up.west(), up.south(), up.north(), up
        };
        for (BlockPos n : neighborsUp) {
            if (remaining.contains(n)) return n;
        }

        return null;
    }

    /**
     * Сортировка «по этажам» с nearest-neighbor внутри этажа.
     * Используется для заполнения полов, потолков и т.д.
     */
    private List<BuildEntry> layerByLayerSort(List<BuildEntry> entries, BlockPos start) {
        // Группируем по Y
        Map<Integer, List<BuildEntry>> byLayer = new TreeMap<>();
        for (BuildEntry e : entries) {
            byLayer.computeIfAbsent(e.pos().getY(), k -> new ArrayList<>()).add(e);
        }

        List<BuildEntry> sorted = new ArrayList<>(entries.size());
        BlockPos current = start;

        for (var layer : byLayer.values()) {
            List<BuildEntry> layerSorted = nearestNeighborSort(layer, current);
            sorted.addAll(layerSorted);
            if (!layerSorted.isEmpty()) {
                current = layerSorted.get(layerSorted.size() - 1).pos();
            }
        }

        return sorted;
    }

    /**
     * Простая Nearest-Neighbor сортировка (жадный алгоритм).
     */
    private List<BuildEntry> nearestNeighborSort(List<BuildEntry> entries, BlockPos start) {
        List<BuildEntry> sorted = new ArrayList<>(entries.size());
        List<BuildEntry> pool = new ArrayList<>(entries);
        BlockPos current = start;

        while (!pool.isEmpty()) {
            int bestIdx = 0;
            double bestDist = Double.MAX_VALUE;

            for (int i = 0; i < pool.size(); i++) {
                double dist = pool.get(i).squaredDistance(current);
                if (dist < bestDist) {
                    bestDist = dist;
                    bestIdx = i;
                }
            }

            BuildEntry chosen = pool.remove(bestIdx);
            sorted.add(chosen);
            current = chosen.pos();
        }

        return sorted;
    }

    /**
     * Находит ближайшую позицию из множества.
     */
    private BlockPos findNearest(BlockPos from, Set<BlockPos> candidates) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        for (BlockPos pos : candidates) {
            double dist = from.getSquaredDistance(pos);
            if (dist < bestDist) {
                bestDist = dist;
                best = pos;
            }
        }

        return best;
    }
}

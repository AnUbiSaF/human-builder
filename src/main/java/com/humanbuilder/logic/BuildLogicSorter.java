package com.humanbuilder.logic;

import net.minecraft.block.*;
import net.minecraft.state.property.Properties;
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

    /** Classifies blocks without running any route-ordering algorithm. */
    public List<BuildEntry> categorize(Map<BlockPos, BlockState> blocks) {
        if (blocks.isEmpty()) return Collections.emptyList();

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos pos : blocks.keySet()) {
            minX = Math.min(minX, pos.getX()); maxX = Math.max(maxX, pos.getX());
            minY = Math.min(minY, pos.getY()); maxY = Math.max(maxY, pos.getY());
            minZ = Math.min(minZ, pos.getZ()); maxZ = Math.max(maxZ, pos.getZ());
        }

        List<BuildEntry> result = new ArrayList<>(blocks.size());
        for (var entry : blocks.entrySet()) {
            result.add(new BuildEntry(
                    entry.getKey(),
                    entry.getValue(),
                    classify(entry.getKey(), entry.getValue(), minX, maxX, minY, maxY, minZ, maxZ, blocks)
            ));
        }
        return result;
    }

    /**
     * Главный метод: сортирует блоки в порядке строительства.
     *
     * @param blocks    карта позиций и состояний из SchematicParser
     * @param playerPos текущая позиция игрока (для nearest-neighbor)
     * @return упорядоченная очередь строительства
     */
    public List<BuildEntry> sort(Map<BlockPos, BlockState> blocks, BlockPos playerPos) {
        return sort(blocks, playerPos, SortMode.LAYERED);
    }

    public List<BuildEntry> sort(Map<BlockPos, BlockState> blocks, BlockPos playerPos, SortMode mode) {
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
        List<BuildEntry> allEntries = new ArrayList<>();
        NavigableMap<Integer, EnumMap<BlockCategory, List<BuildEntry>>> entriesByLayer = new TreeMap<>();
        for (var entry : blocks.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState state = entry.getValue();
            BlockCategory category = classify(pos, state, minX, maxX, minY, maxY, minZ, maxZ, blocks);
            BuildEntry buildEntry = new BuildEntry(pos, state, category);
            allEntries.add(buildEntry);
            entriesByLayer
                    .computeIfAbsent(pos.getY(), ignored -> new EnumMap<>(BlockCategory.class))
                    .computeIfAbsent(category, ignored -> new ArrayList<>())
                    .add(buildEntry);
        }

        List<BuildEntry> result = new ArrayList<>();
        BlockPos currentPos = playerPos;

        if (mode == SortMode.REALISTIC) {
            return realisticConstructionSort(allEntries, playerPos);
        } else if (mode == SortMode.DEFAULT) {
            // Классический режим по категориям
            Map<BlockCategory, List<BuildEntry>> categorized = new EnumMap<>(BlockCategory.class);
            for (BuildEntry entry : allEntries) {
                categorized.computeIfAbsent(entry.category(), k -> new ArrayList<>()).add(entry);
            }
            for (BlockCategory category : BlockCategory.values()) {
                List<BuildEntry> entries = categorized.getOrDefault(category, Collections.emptyList());
                if (entries.isEmpty()) continue;

                List<BuildEntry> sorted;
                if (category == BlockCategory.FOUNDATION || category == BlockCategory.WALL || category == BlockCategory.INTERIOR_WALL) {
                    sorted = wallFollowingSort(entries, currentPos);
                } else {
                    sorted = layerByLayerSort(entries, currentPos);
                }
                result.addAll(sorted);
                if (!sorted.isEmpty()) {
                    currentPos = sorted.get(sorted.size() - 1).pos();
                }
            }
        } else if (mode == SortMode.LAYERED) {
            // Strict bottom-to-top order. Categories only affect ordering
            // inside a layer and can never pull a block from a higher Y.
            for (Map<BlockCategory, List<BuildEntry>> layer : entriesByLayer.values()) {
                for (BlockCategory category : BlockCategory.values()) {
                    List<BuildEntry> entries = layer.getOrDefault(category, Collections.emptyList());
                    if (entries.isEmpty()) continue;

                    List<BuildEntry> sorted;
                    if (category == BlockCategory.FOUNDATION
                            || category == BlockCategory.WALL
                            || category == BlockCategory.INTERIOR_WALL) {
                        sorted = wallFollowingSort(entries, currentPos);
                    } else {
                        sorted = nearestNeighborSort(entries, currentPos);
                    }
                    result.addAll(sorted);
                    currentPos = sorted.get(sorted.size() - 1).pos();
                }
            }
        } else {
            // Смешанный режим по слоям (MIXED)
            // ── PHASE 1: Первый слой полностью (Y = minY) ──
            Map<BlockCategory, List<BuildEntry>> firstLayerCategorized = entriesByLayer.get(minY);
            for (BlockCategory category : BlockCategory.values()) {
                List<BuildEntry> entries = firstLayerCategorized.getOrDefault(category, Collections.emptyList());
                if (entries.isEmpty()) continue;

                List<BuildEntry> sorted;
                if (category == BlockCategory.FOUNDATION || category == BlockCategory.WALL || category == BlockCategory.INTERIOR_WALL) {
                    sorted = wallFollowingSort(entries, currentPos);
                } else {
                    sorted = layerByLayerSort(entries, currentPos);
                }
                result.addAll(sorted);
                if (!sorted.isEmpty()) {
                    currentPos = sorted.get(sorted.size() - 1).pos();
                }
            }

            // Each next layer completes its structural run and filling before
            // moving upward. The previous all-walls-then-all-filling order left
            // MIXED waiting on unsupported upper sections and caused long scans.
            for (Map<BlockCategory, List<BuildEntry>> layer
                    : entriesByLayer.tailMap(minY, false).values()) {
                List<BuildEntry> layerWalls = new ArrayList<>();
                for (BlockCategory category : List.of(
                        BlockCategory.FOUNDATION, BlockCategory.WALL, BlockCategory.INTERIOR_WALL)) {
                    layerWalls.addAll(layer.getOrDefault(category, Collections.emptyList()));
                }
                if (!layerWalls.isEmpty()) {
                    List<BuildEntry> sorted = wallFollowingSort(layerWalls, currentPos);
                    result.addAll(sorted);
                    if (!sorted.isEmpty()) {
                        currentPos = sorted.get(sorted.size() - 1).pos();
                    }
                }

                for (BlockCategory category : BlockCategory.values()) {
                    if (category == BlockCategory.FOUNDATION || category == BlockCategory.WALL
                            || category == BlockCategory.INTERIOR_WALL) continue;
                    List<BuildEntry> entries = layer.getOrDefault(category, Collections.emptyList());
                    if (entries.isEmpty()) continue;

                    List<BuildEntry> sorted = layerByLayerSort(entries, currentPos);
                    result.addAll(sorted);
                    if (!sorted.isEmpty()) {
                        currentPos = sorted.get(sorted.size() - 1).pos();
                    }
                }
            }
        }

        return batchAdjacentSameBlocks(result, playerPos);
    }

    /**
     * Plans construction as support-aware work runs instead of a flat list.
     * A complete lower course unlocks the run above it, structural blocks
     * unlock stairs/slabs and the finished shell unlocks attached decoration.
     */
    private List<BuildEntry> realisticConstructionSort(List<BuildEntry> entries, BlockPos start) {
        Map<BlockPos, BuildEntry> entriesByPos = new HashMap<>();
        for (BuildEntry entry : entries) entriesByPos.put(entry.pos(), entry);

        List<ConstructionRun> runs = collectConstructionRuns(entries, entriesByPos, start);
        Map<BlockPos, Integer> runByPos = new HashMap<>();
        for (ConstructionRun run : runs) {
            for (BuildEntry entry : run.entries) runByPos.put(entry.pos(), run.id);
        }

        for (ConstructionRun run : runs) {
            for (BuildEntry entry : run.entries) {
                addDependencyAt(run, entry.pos().down(), runByPos);

                // Partial and attached blocks are scheduled only after all
                // neighboring structural runs that can serve as support.
                if (run.phase >= 2) {
                    for (BlockPos neighbor : horizontalNeighbors(entry.pos())) {
                        Integer neighborId = runByPos.get(neighbor);
                        if (neighborId != null && neighborId != run.id
                                && runs.get(neighborId).phase < run.phase) {
                            run.dependencies.add(neighborId);
                        }
                    }
                }
            }
        }

        for (ConstructionRun run : runs) {
            run.remainingDependencies = run.dependencies.size();
            for (int dependency : run.dependencies) {
                runs.get(dependency).dependents.add(run.id);
            }
        }

        Comparator<ConstructionRun> priority = Comparator
                .comparingInt((ConstructionRun run) -> run.phase)
                .thenComparingInt(run -> run.y)
                .thenComparingInt(run -> run.category.getPriority())
                .thenComparingDouble(run -> run.startDistance)
                .thenComparingInt(run -> run.anchor.getX())
                .thenComparingInt(run -> run.anchor.getZ());
        PriorityQueue<ConstructionRun> ready = new PriorityQueue<>(priority);
        for (ConstructionRun run : runs) {
            if (run.remainingDependencies == 0) ready.offer(run);
        }

        List<BuildEntry> result = new ArrayList<>(entries.size());
        BlockPos cursor = start;
        while (!ready.isEmpty()) {
            ConstructionRun run = ready.poll();
            if (run.scheduled) continue;

            List<BuildEntry> ordered = orderConstructionRun(run, entriesByPos, cursor);
            result.addAll(ordered);
            if (!ordered.isEmpty()) cursor = ordered.get(ordered.size() - 1).pos();
            run.scheduled = true;

            for (int dependentId : run.dependents) {
                ConstructionRun dependent = runs.get(dependentId);
                dependent.remainingDependencies--;
                if (dependent.remainingDependencies == 0) ready.offer(dependent);
            }
        }

        // Dependencies only point down or to an earlier phase, so a cycle is
        // not expected. Keep a deterministic fallback for malformed schemes.
        if (result.size() < entries.size()) {
            List<ConstructionRun> unresolved = new ArrayList<>();
            for (ConstructionRun run : runs) {
                if (!run.scheduled) unresolved.add(run);
            }
            unresolved.sort(priority);
            for (ConstructionRun run : unresolved) {
                List<BuildEntry> ordered = orderConstructionRun(run, entriesByPos, cursor);
                result.addAll(ordered);
                if (!ordered.isEmpty()) cursor = ordered.get(ordered.size() - 1).pos();
            }
        }
        return result;
    }

    private List<ConstructionRun> collectConstructionRuns(List<BuildEntry> entries,
                                                           Map<BlockPos, BuildEntry> entriesByPos,
                                                           BlockPos start) {
        List<BuildEntry> seeds = new ArrayList<>(entries);
        seeds.sort(Comparator
                .comparingInt((BuildEntry entry) -> entry.pos().getY())
                .thenComparingInt(entry -> constructionPhase(entry))
                .thenComparingInt(entry -> entry.category().getPriority())
                .thenComparingInt(entry -> entry.pos().getX())
                .thenComparingInt(entry -> entry.pos().getZ()));

        Set<BlockPos> unassigned = new HashSet<>(entriesByPos.keySet());
        List<ConstructionRun> runs = new ArrayList<>();
        for (BuildEntry seed : seeds) {
            if (!unassigned.remove(seed.pos())) continue;

            List<BuildEntry> runEntries = new ArrayList<>();
            ArrayDeque<BlockPos> open = new ArrayDeque<>();
            open.add(seed.pos());
            while (!open.isEmpty()) {
                BlockPos pos = open.removeFirst();
                BuildEntry current = entriesByPos.get(pos);
                runEntries.add(current);

                for (BlockPos neighbor : horizontalNeighbors(pos)) {
                    BuildEntry candidate = entriesByPos.get(neighbor);
                    if (candidate != null && unassigned.contains(neighbor)
                            && belongsToSameRun(seed, candidate)) {
                        unassigned.remove(neighbor);
                        open.addLast(neighbor);
                    }
                }
            }
            runs.add(new ConstructionRun(
                    runs.size(), runEntries, constructionPhase(seed), start));
        }
        return runs;
    }

    private void addDependencyAt(ConstructionRun run, BlockPos dependencyPos,
                                 Map<BlockPos, Integer> runByPos) {
        Integer dependencyId = runByPos.get(dependencyPos);
        if (dependencyId != null && dependencyId != run.id) {
            run.dependencies.add(dependencyId);
        }
    }

    private List<BuildEntry> orderConstructionRun(ConstructionRun run,
                                                   Map<BlockPos, BuildEntry> entriesByPos,
                                                   BlockPos start) {
        Block block = run.entries.get(0).state().getBlock();
        if (block instanceof StairsBlock || block instanceof SlabBlock
                || run.category == BlockCategory.WALL
                || run.category == BlockCategory.INTERIOR_WALL
                || run.category == BlockCategory.DECOR) {
            return spatialSweepSort(run.entries, start);
        }

        Set<BlockPos> positions = new HashSet<>();
        for (BuildEntry entry : run.entries) positions.add(entry.pos());
        return orderComponentInsideOut(positions, entriesByPos, start);
    }

    private boolean belongsToSameRun(BuildEntry first, BuildEntry second) {
        return first.pos().getY() == second.pos().getY()
                && first.category() == second.category()
                && constructionPhase(first) == constructionPhase(second)
                && samePlacementSequenceState(first.state(), second.state());
    }

    private int constructionPhase(BuildEntry entry) {
        if (entry.category() == BlockCategory.FOUNDATION) return 0;
        if (entry.category() == BlockCategory.DECOR) return 3;
        if (entry.category() == BlockCategory.CEILING
                || entry.state().getBlock() instanceof StairsBlock
                || entry.state().getBlock() instanceof SlabBlock) return 2;
        return 1;
    }

    private boolean samePlacementSequenceState(BlockState first, BlockState second) {
        if (first.getBlock() != second.getBlock()) return false;
        if (first.getBlock() instanceof StairsBlock) {
            return first.get(Properties.HORIZONTAL_FACING) == second.get(Properties.HORIZONTAL_FACING)
                    && first.get(Properties.BLOCK_HALF) == second.get(Properties.BLOCK_HALF);
        }
        if (first.getBlock() instanceof SlabBlock) {
            return first.get(Properties.SLAB_TYPE) == second.get(Properties.SLAB_TYPE);
        }
        return first.equals(second);
    }

    private static final class ConstructionRun {
        private final int id;
        private final List<BuildEntry> entries;
        private final int phase;
        private final int y;
        private final BlockCategory category;
        private final BlockPos anchor;
        private final double startDistance;
        private final Set<Integer> dependencies = new HashSet<>();
        private final List<Integer> dependents = new ArrayList<>();
        private int remainingDependencies;
        private boolean scheduled;

        private ConstructionRun(int id, List<BuildEntry> entries, int phase, BlockPos start) {
            this.id = id;
            this.entries = entries;
            BuildEntry first = entries.get(0);
            this.phase = phase;
            this.y = first.pos().getY();
            this.category = first.category();
            this.anchor = entries.stream()
                    .map(BuildEntry::pos)
                    .min(Comparator.comparingInt(BlockPos::getX)
                            .thenComparingInt(BlockPos::getZ))
                    .orElse(first.pos());
            this.startDistance = entries.stream()
                    .mapToDouble(entry -> entry.squaredDistance(start))
                    .min()
                    .orElse(Double.MAX_VALUE);
        }
    }

    /**
     * Keeps each horizontally connected group with the same placement state together.
     * Less exposed cells are placed first so the outer edge does not hide the
     * remaining cells of a dense floor or wall from the player.
     */
    private List<BuildEntry> batchAdjacentSameBlocks(List<BuildEntry> ordered, BlockPos start) {
        if (ordered.size() < 2) return ordered;

        Map<BlockPos, BuildEntry> byPos = new HashMap<>();
        for (BuildEntry entry : ordered) byPos.put(entry.pos(), entry);

        Set<BlockPos> remaining = new HashSet<>(byPos.keySet());
        List<BuildEntry> batched = new ArrayList<>(ordered.size());
        BlockPos current = start;

        for (BuildEntry seed : ordered) {
            if (!remaining.contains(seed.pos())) continue;

            Set<BlockPos> component = collectSameBlockComponent(seed, byPos, remaining);
            List<BuildEntry> safeOrder = orderComponentInsideOut(component, byPos, current);
            batched.addAll(safeOrder);
            remaining.removeAll(component);
            if (!safeOrder.isEmpty()) current = safeOrder.get(safeOrder.size() - 1).pos();
        }
        return batched;
    }

    private Set<BlockPos> collectSameBlockComponent(BuildEntry seed, Map<BlockPos, BuildEntry> byPos,
                                                     Set<BlockPos> remaining) {
        Set<BlockPos> component = new HashSet<>();
        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        open.add(seed.pos());

        while (!open.isEmpty()) {
            BlockPos pos = open.removeFirst();
            if (!remaining.contains(pos) || !component.add(pos)) continue;

            for (BlockPos neighbor : horizontalNeighbors(pos)) {
                BuildEntry candidate = byPos.get(neighbor);
                if (candidate != null
                        && candidate.pos().getY() == seed.pos().getY()
                        && samePlacementSequenceState(candidate.state(), seed.state())) {
                    open.addLast(neighbor);
                }
            }
        }
        return component;
    }

    private List<BuildEntry> orderComponentInsideOut(Set<BlockPos> component,
                                                      Map<BlockPos, BuildEntry> allEntries,
                                                      BlockPos start) {
        Map<Integer, List<BuildEntry>> byExposure = new TreeMap<>();
        for (BlockPos pos : component) {
            byExposure
                    .computeIfAbsent(exposedSideCount(pos, allEntries), ignored -> new ArrayList<>())
                    .add(allEntries.get(pos));
        }

        List<BuildEntry> result = new ArrayList<>(component.size());
        BlockPos cursor = start;
        for (List<BuildEntry> exposureBand : byExposure.values()) {
            List<BuildEntry> swept = spatialSweepSort(exposureBand, cursor);
            result.addAll(swept);
            if (!swept.isEmpty()) cursor = swept.get(swept.size() - 1).pos();
        }
        return result;
    }

    private int exposedSideCount(BlockPos pos, Map<BlockPos, BuildEntry> allEntries) {
        int exposed = 0;
        for (BlockPos neighbor : horizontalNeighbors(pos)) {
            if (!allEntries.containsKey(neighbor)) exposed++;
        }
        return exposed;
    }

    private BlockPos[] horizontalNeighbors(BlockPos pos) {
        return new BlockPos[]{pos.east(), pos.west(), pos.south(), pos.north()};
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
        return spatialSweepSort(entries, start);
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
        return spatialSweepSort(entries, start);
    }

    /**
     * Orders each layer in alternating rows. This preserves short, continuous
     * build runs without the O(n^2) nearest-neighbour search used previously.
     */
    private List<BuildEntry> spatialSweepSort(List<BuildEntry> entries, BlockPos start) {
        if (entries.size() < 2) return new ArrayList<>(entries);

        Map<Integer, List<BuildEntry>> layers = new TreeMap<>();
        for (BuildEntry entry : entries) {
            layers.computeIfAbsent(entry.pos().getY(), ignored -> new ArrayList<>()).add(entry);
        }

        List<BuildEntry> result = new ArrayList<>(entries.size());
        BlockPos cursor = start;
        for (List<BuildEntry> layer : layers.values()) {
            List<BuildEntry> swept = sweepLayer(layer, cursor);
            result.addAll(swept);
            if (!swept.isEmpty()) cursor = swept.get(swept.size() - 1).pos();
        }
        return result;
    }

    private List<BuildEntry> sweepLayer(List<BuildEntry> layer, BlockPos start) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (BuildEntry entry : layer) {
            minX = Math.min(minX, entry.pos().getX());
            maxX = Math.max(maxX, entry.pos().getX());
            minZ = Math.min(minZ, entry.pos().getZ());
            maxZ = Math.max(maxZ, entry.pos().getZ());
        }

        boolean rowsAlongX = maxX - minX >= maxZ - minZ;
        Map<Integer, List<BuildEntry>> rows = new TreeMap<>();
        for (BuildEntry entry : layer) {
            int row = rowsAlongX ? entry.pos().getZ() : entry.pos().getX();
            rows.computeIfAbsent(row, ignored -> new ArrayList<>()).add(entry);
        }

        List<Integer> rowOrder = new ArrayList<>(rows.keySet());
        int startRow = rowsAlongX ? start.getZ() : start.getX();
        if (Math.abs(startRow - rowOrder.get(rowOrder.size() - 1))
                < Math.abs(startRow - rowOrder.get(0))) {
            Collections.reverse(rowOrder);
        }

        int startAlong = rowsAlongX ? start.getX() : start.getZ();
        int minAlong = rowsAlongX ? minX : minZ;
        int maxAlong = rowsAlongX ? maxX : maxZ;
        boolean ascending = Math.abs(startAlong - minAlong) <= Math.abs(startAlong - maxAlong);
        Comparator<BuildEntry> alongComparator = rowsAlongX
                ? Comparator.comparingInt((BuildEntry entry) -> entry.pos().getX())
                    .thenComparingInt(entry -> entry.pos().getZ())
                : Comparator.comparingInt((BuildEntry entry) -> entry.pos().getZ())
                    .thenComparingInt(entry -> entry.pos().getX());

        List<BuildEntry> result = new ArrayList<>(layer.size());
        for (int row : rowOrder) {
            List<BuildEntry> entries = rows.get(row);
            entries.sort(alongComparator);
            if (!ascending) Collections.reverse(entries);
            result.addAll(entries);
            ascending = !ascending;
        }
        return result;
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

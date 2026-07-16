package com.humanbuilder.parser;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.io.File;
import java.util.*;

/**
 * Парсер файлов .litematic (Litematica) и .schematic (Sponge v2).
 *
 * Читает NBT-структуру, декодирует bit-packed массив блоков
 * и возвращает Map<BlockPos, BlockState> — полную карту постройки.
 *
 * Формат .litematic:
 *   Root → Regions → {regionName} → BlockStatePalette + BlockStates(LongArray)
 *   Палитра содержит Name + Properties для каждого типа блока.
 *   Массив BlockStates — bit-packed индексы палитры.
 */
public class SchematicParser {

    /**
     * Загружает схему из файла. Определяет формат по расширению.
     *
     * @param file файл .litematic или .schematic
     * @return карта позиций и состояний блоков (без воздуха)
     */
    public Map<BlockPos, BlockState> parse(File file) throws Exception {
        String name = file.getName().toLowerCase();

        NbtCompound root = NbtIo.readCompressed(file.toPath(), NbtSizeTracker.ofUnlimitedBytes());

        // WorldEdit .schem/.schematic файлы часто оборачивают все данные в корневой тег "Schematic"
        if (root.contains("Schematic")) {
            root = root.getCompound("Schematic");
        }

        if (name.endsWith(".litematic")) {
            return parseLitematic(root);
        } else if (name.endsWith(".schematic") || name.endsWith(".schem")) {
            return parseSponge(root);
        } else {
            throw new IllegalArgumentException("Неизвестный формат: " + name);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  Litematic (.litematic)
    // ════════════════════════════════════════════════════════════════════

    private Map<BlockPos, BlockState> parseLitematic(NbtCompound root) {
        Map<BlockPos, BlockState> result = new HashMap<>();

        NbtCompound regions = root.getCompound("Regions");
        if (regions == null) return result;

        for (String regionName : regions.getKeys()) {
            NbtCompound region = regions.getCompound(regionName);
            parseLitematicRegion(region, result);
        }

        return result;
    }

    private void parseLitematicRegion(NbtCompound region, Map<BlockPos, BlockState> result) {
        // ── Позиция и размер региона ───────────────────────────────────
        NbtCompound posTag  = region.getCompound("Position");
        NbtCompound sizeTag = region.getCompound("Size");

        int posX = posTag.getInt("x");
        int posY = posTag.getInt("y");
        int posZ = posTag.getInt("z");

        int sizeX = sizeTag.getInt("x");
        int sizeY = sizeTag.getInt("y");
        int sizeZ = sizeTag.getInt("z");

        // Размеры могут быть отрицательными (направление выделения)
        int absSizeX = Math.abs(sizeX);
        int absSizeY = Math.abs(sizeY);
        int absSizeZ = Math.abs(sizeZ);

        int originX = sizeX < 0 ? posX + sizeX + 1 : posX;
        int originY = sizeY < 0 ? posY + sizeY + 1 : posY;
        int originZ = sizeZ < 0 ? posZ + sizeZ + 1 : posZ;

        // ── Палитра блоков ─────────────────────────────────────────────
        NbtList paletteList = region.getList("BlockStatePalette", 10); // 10 = TAG_Compound
        BlockState[] palette = new BlockState[paletteList.size()];

        for (int i = 0; i < paletteList.size(); i++) {
            NbtCompound entry = paletteList.getCompound(i);
            palette[i] = nbtToBlockState(entry);
        }

        // ── Bit-packed BlockStates ─────────────────────────────────────
        long[] blockStates = region.getLongArray("BlockStates");
        int totalBlocks = absSizeX * absSizeY * absSizeZ;
        int bitsPerEntry = Math.max(2, ceilLog2(palette.length));

        for (int index = 0; index < totalBlocks; index++) {
            int paletteIndex = extractPaletteIndex(blockStates, index, bitsPerEntry);
            if (paletteIndex < 0 || paletteIndex >= palette.length) continue;

            BlockState state = palette[paletteIndex];

            // Пропускаем воздух
            if (state.isAir()) continue;

            // Декодируем 1D индекс → 3D координаты (порядок Litematica: X, Z, Y)
            int localY = index / (absSizeX * absSizeZ);
            int remain = index % (absSizeX * absSizeZ);
            int localZ = remain / absSizeX;
            int localX = remain % absSizeX;

            BlockPos worldPos = new BlockPos(
                    originX + localX,
                    originY + localY,
                    originZ + localZ
            );

            result.put(worldPos, state);
        }
    }

    /**
     * Извлекает индекс палитры из bit-packed long[].
     *
     * Litematica v5+ использует «compact» формат (как MC 1.16+):
     * каждый long содержит floor(64 / bitsPerEntry) записей,
     * записи НЕ пересекают границы long'ов.
     */
    private int extractPaletteIndex(long[] data, int index, int bitsPerEntry) {
        int entriesPerLong = 64 / bitsPerEntry;
        long mask = (1L << bitsPerEntry) - 1;

        int longIndex = index / entriesPerLong;
        int bitOffset = (index % entriesPerLong) * bitsPerEntry;

        if (longIndex >= data.length) return 0;

        return (int) ((data[longIndex] >> bitOffset) & mask);
    }

    // ════════════════════════════════════════════════════════════════════
    //  Sponge Schematic (.schematic / .schem)
    // ════════════════════════════════════════════════════════════════════

    private Map<BlockPos, BlockState> parseSponge(NbtCompound root) {
        Map<BlockPos, BlockState> result = new HashMap<>();

        int width  = getNumeric(root, "Width");
        int height = getNumeric(root, "Height");
        int length = getNumeric(root, "Length");

        NbtCompound paletteTag;
        byte[] blockData;

        // Sponge v3 использует compound "Blocks" для хранения палитры и блоков
        if (root.contains("Blocks", 10)) { // 10 = COMPOUND
            NbtCompound blocksTag = root.getCompound("Blocks");
            paletteTag = blocksTag.getCompound("Palette");
            blockData = blocksTag.getByteArray("Data");
        } else {
            // Sponge v2 использует корневые теги
            paletteTag = root.getCompound("Palette");
            blockData = root.getByteArray("BlockData");
        }

        if (paletteTag == null || blockData == null || blockData.length == 0) {
            return result;
        }

        // Инвертируем палитру: index → BlockState
        Map<Integer, BlockState> palette = new HashMap<>();
        for (String key : paletteTag.getKeys()) {
            int index = paletteTag.getInt(key);
            palette.put(index, stringToBlockState(key));
        }

        // Декодируем блоки (varInt encoding в Sponge v2/v3)
        int dataIndex = 0;
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    if (dataIndex >= blockData.length) break;

                    // VarInt decoding
                    int value = 0;
                    int shift = 0;
                    int b;
                    do {
                        if (dataIndex >= blockData.length) break;
                        b = blockData[dataIndex++] & 0xFF;
                        value |= (b & 0x7F) << shift;
                        shift += 7;
                    } while ((b & 0x80) != 0);

                    BlockState state = palette.get(value);
                    if (state != null && !state.isAir()) {
                        result.put(new BlockPos(x, y, z), state);
                    }
                }
            }
        }

        return result;
    }

    // ════════════════════════════════════════════════════════════════════
    //  Утилиты
    // ════════════════════════════════════════════════════════════════════

    /**
     * Преобразует NBT-запись палитры в BlockState.
     * Пример NBT: {Name:"minecraft:oak_stairs", Properties:{facing:"east",half:"top"}}
     */
    private BlockState nbtToBlockState(NbtCompound nbt) {
        String name = nbt.getString("Name");
        Block block = Registries.BLOCK.get(Identifier.of(name));
        BlockState state = block.getDefaultState();

        if (nbt.contains("Properties")) {
            NbtCompound props = nbt.getCompound("Properties");
            state = applyProperties(state, block, props);
        }

        return state;
    }

    /**
     * Преобразует строку формата "minecraft:stone" или
     * "minecraft:oak_stairs[facing=east,half=top]" в BlockState.
     */
    private BlockState stringToBlockState(String str) {
        String blockId;
        Map<String, String> props = new HashMap<>();

        int bracketStart = str.indexOf('[');
        if (bracketStart != -1) {
            blockId = str.substring(0, bracketStart);
            String propsStr = str.substring(bracketStart + 1, str.length() - 1);
            for (String pair : propsStr.split(",")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) {
                    props.put(kv[0].trim(), kv[1].trim());
                }
            }
        } else {
            blockId = str;
        }

        Block block = Registries.BLOCK.get(Identifier.of(blockId));
        BlockState state = block.getDefaultState();

        if (!props.isEmpty()) {
            StateManager<Block, BlockState> sm = block.getStateManager();
            for (var entry : props.entrySet()) {
                Property<?> property = sm.getProperty(entry.getKey());
                if (property != null) {
                    state = applyProperty(state, property, entry.getValue());
                }
            }
        }

        return state;
    }

    /**
     * Применяет все свойства из NBT к BlockState.
     */
    private BlockState applyProperties(BlockState state, Block block, NbtCompound propsNbt) {
        StateManager<Block, BlockState> sm = block.getStateManager();
        for (String key : propsNbt.getKeys()) {
            Property<?> property = sm.getProperty(key);
            if (property != null) {
                state = applyProperty(state, property, propsNbt.getString(key));
            }
        }
        return state;
    }

    /**
     * Безопасно применяет одно свойство к BlockState.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState applyProperty(BlockState state, Property property, String value) {
        Optional<?> parsed = property.parse(value);
        if (parsed.isPresent()) {
            return state.with(property, (Comparable) parsed.get());
        }
        return state;
    }

    /**
     * ceil(log2(n)) — минимальное количество бит для представления n значений.
     */
    private static int ceilLog2(int n) {
        if (n <= 1) return 1;
        return 32 - Integer.numberOfLeadingZeros(n - 1);
    }

    /**
     * Безопасно читает числовое значение из NBT с приведением к int.
     */
    private static int getNumeric(NbtCompound tag, String key) {
        if (tag.contains(key, 99)) { // 99 = Any Numeric Type (Byte, Short, Int, Long, Float, Double)
            net.minecraft.nbt.NbtElement el = tag.get(key);
            if (el instanceof net.minecraft.nbt.AbstractNbtNumber number) {
                return number.intValue();
            }
        }
        return 0;
    }
}

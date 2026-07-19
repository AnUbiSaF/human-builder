package com.humanbuilder.logic;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.EndRodBlock;
import net.minecraft.block.LeavesBlock;
import net.minecraft.registry.Registries;

/** Shared policy for blocks that must never participate in the frame phase. */
public final class BlockRoleClassifier {

    private BlockRoleClassifier() {}

    public static boolean isAlwaysDecor(BlockState state) {
        if (state == null) return false;
        if (state.isReplaceable()) return true;
        Block block = state.getBlock();
        if (block instanceof EndRodBlock || block instanceof LeavesBlock) return true;
        return isAlwaysDecorPath(Registries.BLOCK.getId(block).getPath());
    }

    static boolean isAlwaysDecorPath(String path) {
        return "end_rod".equals(path) || path.endsWith("_leaves");
    }
}

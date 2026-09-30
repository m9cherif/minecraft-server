package com.baarcha.arcade;

import org.bukkit.World;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;

import java.util.List;

/**
 * Pure-void chunk generator for arcade_world — same trick as the hub's
 * overworld and agame_world. Every arena is stamped in procedurally by its
 * GameMode.build(), so the generator itself must emit nothing at all.
 */
public final class VoidGenerator extends ChunkGenerator {

    @Override
    public boolean shouldGenerateNoise() { return false; }

    @Override
    public boolean shouldGenerateSurface() { return false; }

    @Override
    public boolean shouldGenerateBedrock() { return false; }

    @Override
    public boolean shouldGenerateCaves() { return false; }

    @Override
    public boolean shouldGenerateDecorations() { return false; }

    @Override
    public List<BlockPopulator> getDefaultPopulators(World world) {
        return List.of();
    }
}

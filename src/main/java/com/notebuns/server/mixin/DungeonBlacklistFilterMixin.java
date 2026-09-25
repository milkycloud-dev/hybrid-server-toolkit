package com.notebuns.server.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Генерация Aether без ожидания соседних чанков.
 *
 * Фильтр DungeonBlacklistFilter спрашивает, нет ли на месте декорации подземелья, у
 * менеджера структур всего мира. На шаге декораций тот синхронно дозагружает соседние
 * чанки и ждёт их, поэтому Aether генерировался в ~40 раз медленнее обычного мира (spark,
 * 2026-09-23: 53 из 60 с потоков генерации в LevelReader.getChunk). Фильтр получает
 * менеджер, привязанный к области генерации, как делает сама ванилла на этом шаге.
 */
@Mixin(targets = "com.aetherteam.aether.world.placementmodifier.DungeonBlacklistFilter")
public abstract class DungeonBlacklistFilterMixin {
    @Redirect(method = "shouldPlace", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ServerLevel;structureManager()Lnet/minecraft/world/level/StructureManager;"))
    private StructureManager notebuns$regionStructures(ServerLevel level, PlacementContext context, RandomSource random, BlockPos pos) {
        if (context.getLevel() instanceof WorldGenRegion region) {
            return level.structureManager().forWorldGenRegion(region);
        }
        return level.structureManager();
    }
}

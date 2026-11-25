package com.notebuns.server.mixin;

import com.notebuns.server.ContainerLog;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public abstract class LevelChunkContainerLogMixin {
    @Inject(method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;", at = @At("HEAD"))
    private void notebuns$logContainer(BlockPos pos, BlockState state, boolean moving, CallbackInfoReturnable<BlockState> cir) {
        ContainerLog.beforeSetBlock((LevelChunk) (Object) this, pos, state, moving);
    }
}

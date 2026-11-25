package com.notebuns.server.mixin;

import com.notebuns.server.ContainerLog;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class StopServerContainerLogMixin {
    @Inject(method = "stopServer()V", at = @At("TAIL"))
    private void notebuns$flushContainerLog(CallbackInfo ci) {
        ContainerLog.afterStopServer();
    }
}

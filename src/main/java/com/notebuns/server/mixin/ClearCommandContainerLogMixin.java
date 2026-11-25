package com.notebuns.server.mixin;

import com.notebuns.server.ContainerLog;
import net.minecraft.server.commands.CloneCommands;
import net.minecraft.server.commands.FillCommand;
import net.minecraft.server.commands.SetBlockCommand;
import net.minecraft.world.Clearable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin({SetBlockCommand.class, FillCommand.class, CloneCommands.class})
public abstract class ClearCommandContainerLogMixin {
    @Redirect(method = "*", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/Clearable;tryClear(Ljava/lang/Object;)V"), require = 0)
    private static void notebuns$logBeforeClear(Object target) {
        ContainerLog.beforeCommandClear(target);
        Clearable.tryClear(target);
    }
}

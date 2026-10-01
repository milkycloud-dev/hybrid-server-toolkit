package com.notebuns.server.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Клик по слоту, которого нет в Bukkit-представлении окна.
 *
 * Окно аксессуаров Aether наследует InventoryMenu и добавляет к 46 ванильным слотам свои.
 * Youer строит для него обычное представление инвентаря игрока на 46 слотов, и
 * getInventory(51) бросает IllegalArgumentException в каждом плагине, который слушает
 * InventoryClickEvent (WorldGuard, ImageFrame). Такой слот отдаём как клик вне окна:
 * null, как Bukkit и документирует для getClickedInventory.
 */
@Mixin(targets = "org.bukkit.craftbukkit.inventory.CraftAbstractInventoryView", remap = false)
public abstract class BukkitViewSlotMixin {
    @Unique
    private static final Logger notebuns$LOG = LoggerFactory.getLogger("NoteBunsServer");
    @Unique
    private static boolean notebuns$logged;

    @Shadow
    public abstract int countSlots();

    @Inject(method = "getInventory(I)Lorg/bukkit/inventory/Inventory;", at = @At("HEAD"), cancellable = true, require = 0)
    private void notebuns$slotOutsideView(int rawSlot, CallbackInfoReturnable<Object> cir) {
        int count = countSlots();
        if (rawSlot >= count) {
            if (!notebuns$logged) {
                notebuns$logged = true;
                notebuns$LOG.info("Слот {} вне Bukkit-окна на {} слотов, считаю кликом вне окна (дальше без записи)", rawSlot, count);
            }
            cir.setReturnValue(null);
        }
    }
}

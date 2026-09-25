package com.notebuns.server;

import com.mojang.logging.LogUtils;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.ServerRecipeBook;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.slf4j.Logger;

import java.util.List;
import java.util.Optional;

/**
 * Книга рецептов не переживает смерть.
 *
 * Youer при возрождении создаёт нового ServerPlayer и переносит в него данные
 * старого (restoreFrom), но строку с книгой рецептов из ванили у себя выкинул.
 * Это наследие CraftBukkit, где игрок при возрождении тот же объект. После каждой смерти
 * книга пустая, рецепты «открываются заново».
 *
 * - При клонировании игрока книга копируется из старого объекта (NeoForge зовёт
 *   PlayerEvent.Clone изнутри restoreFrom). Если Youer отдал тот же объект,
 *   ничего не делать: copyOverData сам в себя стирает книгу.
 * - При входе в книгу возвращаются рецепты из выполненных достижений, то есть то, что
 *   уже стёрлось раньше. Без всплывашек: клиенту уходит полная книга (INIT).
 */
final class RecipeBookFix {
    private static final Logger LOGGER = LogUtils.getLogger();

    @SubscribeEvent
    public void onClone(PlayerEvent.Clone event) {
        if (event.getEntity() instanceof ServerPlayer now && event.getOriginal() instanceof ServerPlayer before
                && now != before) {
            now.getRecipeBook().copyOverData(before.getRecipeBook());
        }
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        ServerRecipeBook book = player.getRecipeBook();
        RecipeManager recipes = server.getRecipeManager();
        int restored = 0;
        for (AdvancementHolder advancement : server.getAdvancements().getAllAdvancements()) {
            List<ResourceLocation> ids = advancement.value().rewards().recipes();
            if (ids.isEmpty() || !player.getAdvancements().getOrStartProgress(advancement).isDone()) {
                continue;
            }
            for (ResourceLocation id : ids) {
                if (book.contains(id)) {
                    continue;
                }
                Optional<RecipeHolder<?>> recipe = recipes.byKey(id);
                if (recipe.isPresent()) {
                    book.add(recipe.get());
                    if (book.contains(id)) {
                        restored++;
                    }
                }
            }
        }
        if (restored > 0) {
            book.sendInitialRecipeBook(player);
            LOGGER.info("[NoteBuns] книга рецептов {}: возвращено {} из выполненных достижений",
                player.getGameProfile().getName(), restored);
        }
    }
}

package com.notebuns.server;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Серверные правки, которых нет в модах.
 *
 * - Генерация Aether без ожидания соседних чанков (миксин DungeonBlacklistFilterMixin).
 * - Очередь прегенерации поверх Chunky: не больше N миров одновременно, остальные ждут,
 *   после перезапуска сервера очередь продолжается (PregenQueue).
 *   Готовым мир считается только после проверки файлов регионов: все чанки full (RegionCheck).
 * - Книга рецептов переживает смерть; стёртое раньше возвращается при входе (RecipeBookFix).
 * - Сервер остановился, а процесс висит: через 90 с принудительный выход (ExitWatchdog).
 * - Клиент получает свою позицию после возрождения (RespawnSync).
 * - Правила смерти в измерениях как в верхнем мире (DeathRulesSync).
 * - Полный снимок игрока при смерти, с модовыми слотами, и /deathbackup (DeathBackup).
 */
@Mod(value = NoteBunsServer.MOD_ID, dist = Dist.DEDICATED_SERVER)
public final class NoteBunsServer {
    public static final String MOD_ID = "notebuns_server";

    public NoteBunsServer(IEventBus modBus) {
        NeoForge.EVENT_BUS.register(new RecipeBookFix());
        NeoForge.EVENT_BUS.register(new ExitWatchdog());
        NeoForge.EVENT_BUS.register(new RespawnSync());
        NeoForge.EVENT_BUS.register(new DeathRulesSync());
        NeoForge.EVENT_BUS.register(new DeathBackup());
        if (ModList.get().isLoaded("chunky")) {
            NeoForge.EVENT_BUS.register(new PregenQueue());
        }
    }
}

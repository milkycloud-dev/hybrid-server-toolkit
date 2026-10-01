package com.notebuns.server;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;

import java.util.List;

/**
 * Правила смерти во всех измерениях как в верхнем мире.
 *
 * В Youer у каждого мира свои правила игры, и они разъехались. На 01.10.2026:
 * ад и Сумеречный лес doImmediateRespawn=true и showDeathMessages=false, Энд
 * doImmediateRespawn=true, Марс showDeathMessages=false. Отсюда смерть в аду без
 * сообщения в чате (нокаут Hardcore Revival тоже молчит) и без экрана смерти.
 * Модовые измерения своего level.dat не имеют, правило, выставленное командой,
 * может не пережить перезапуск, поэтому выравнивание идёт при каждом старте.
 * Через команду: в Youer у GameRules.BooleanValue.set другая сигнатура.
 */
final class DeathRulesSync {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final List<GameRules.Key<GameRules.BooleanValue>> RULES =
        List.of(GameRules.RULE_DO_IMMEDIATE_RESPAWN, GameRules.RULE_SHOWDEATHMESSAGES);

    @SubscribeEvent
    public void onStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        ServerLevel overworld = server.overworld();
        for (GameRules.Key<GameRules.BooleanValue> rule : RULES) {
            boolean want = overworld.getGameRules().getBoolean(rule);
            for (ServerLevel level : server.getAllLevels()) {
                if (level == overworld || level.getGameRules().getBoolean(rule) == want) {
                    continue;
                }
                try {
                    server.getCommands().performPrefixedCommand(
                        server.createCommandSourceStack().withLevel(level).withSuppressedOutput(),
                        "gamerule " + rule.getId() + " " + want);
                } catch (RuntimeException e) {
                    LOGGER.warn("[NoteBuns] {} в {}: команда не прошла", rule.getId(), level.dimension().location(), e);
                }
                boolean now = level.getGameRules().getBoolean(rule);
                LOGGER.info("[NoteBuns] {} в {}: {} -> {}{}", rule.getId(), level.dimension().location(),
                    !want, now, now == want ? "" : " (не изменилось)");
            }
        }
    }
}

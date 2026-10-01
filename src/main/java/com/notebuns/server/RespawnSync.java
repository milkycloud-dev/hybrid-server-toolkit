package com.notebuns.server;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.slf4j.Logger;

/**
 * После возрождения клиент не получает свою позицию.
 *
 * Youer возрождает по-неофоржевски: PlayerList.respawn создаёт нового ServerPlayer,
 * а connection.player переключается на него только после возврата из respawn.
 * Внутри respawn позиция уходит клиенту через connection.teleport, и проверка Paper
 * «телепорт удалённого игрока» его режет: connection.player в этот момент старый,
 * уже удалённый объект. В логе после каждой смерти:
 * «Attempt to teleport removed player X restricted».
 *
 * Клиент без пакета позиции ставит нового игрока в точку спавна своего мира:
 * (8.5, 65, 8.5) после смены измерения, иначе последнюю присланную сервером точку,
 * и поднимает до первого свободного места. Сервер принимает его движение как настоящее
 * (moved-too-quickly-multiplier 1000 в spigot.yml), разница высот с точкой возрождения
 * засчитывается как падение: «разбился насмерть», иногда по кругу.
 *
 * NeoForge зовёт PlayerEvent.Clone из restoreFrom, то есть до телепорта. Здесь
 * connection.player переключается на нового игрока, телепорт проходит, сервер ждёт
 * подтверждения клиента и до него не принимает чужие координаты, как в ванили.
 * Путь Bukkit (тот же объект игрока) сюда не попадает: там now == before.
 */
final class RespawnSync {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean logged;

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onClone(PlayerEvent.Clone event) {
        if (!(event.getEntity() instanceof ServerPlayer now) || !(event.getOriginal() instanceof ServerPlayer before)
                || now == before || now.connection == null || now.connection.player != before) {
            return;
        }
        now.connection.player = now;
        if (!logged) {
            logged = true;
            LOGGER.info("[NoteBuns] возрождение {}: соединение переключено на нового игрока до телепорта (дальше без записи)",
                now.getGameProfile().getName());
        }
    }
}

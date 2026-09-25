package com.notebuns.server;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;

/**
 * Сервер остановился, а процесс нет.
 *
 * 2026-09-23/24: дважды нехватка памяти ударила во время выключения («Ошибка при
 * остановке сервера»), выключение оборвалось, и 26 фоновых потоков плагинов и ядра
 * (bStats, асинхронный планировщик, IO-Worker…) держали java живой. start.sh не
 * возвращался, панель считала сервер работающим, и он лежал по 3-4 часа.
 *
 * Здесь фоновый поток смотрит на основной поток сервера: если тот завершился, а процесс
 * жив дольше GRACE_MS, процесс завершается с кодом 1. Панель видит падение и
 * перезапускает. При обычной остановке java выходит сама за секунды, до сторожа не доходит.
 */
final class ExitWatchdog {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long GRACE_MS = 90_000L;
    private static final long CHECK_MS = 5_000L;
    private boolean started;

    @SubscribeEvent
    public void onStarted(ServerStartedEvent event) {
        if (started) {
            return;
        }
        started = true;
        MinecraftServer server = event.getServer();
        Thread main = server.getRunningThread();
        Thread watcher = new Thread(() -> watch(main), "NoteBuns Exit Watchdog");
        watcher.setDaemon(true); // сам сторож выходу не мешает
        watcher.start();
    }

    private static void watch(Thread main) {
        long deadSince = 0L;
        while (true) {
            try {
                Thread.sleep(CHECK_MS);
            } catch (InterruptedException e) {
                return;
            }
            if (main.isAlive()) {
                deadSince = 0L;
                continue;
            }
            long now = System.currentTimeMillis();
            if (deadSince == 0L) {
                deadSince = now;
                LOGGER.warn("[NoteBuns] основной поток сервера завершился, жду выхода java {} с", GRACE_MS / 1000);
            } else if (now - deadSince >= GRACE_MS) {
                LOGGER.error("[NoteBuns] сервер остановлен, а процесс жив {} с: принудительный выход, чтобы панель перезапустила",
                    (now - deadSince) / 1000);
                Runtime.getRuntime().halt(1);
            }
        }
    }
}

package com.notebuns.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.popcraft.chunky.ChunkyProvider;
import org.popcraft.chunky.api.ChunkyAPI;
import org.slf4j.Logger;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Очередь прегенерации поверх Chunky.
 *
 * Chunky запускает задачи сразу и все вместе; у Chunky нет очереди. Здесь список
 * миров из config/notebuns_pregen.json идёт по порядку: одновременно не больше
 * concurrent задач, как только одна заканчивается, стартует следующая. Прогресс
 * начатых задач Chunky хранит сам и продолжает после перезапуска
 * (continueOnRestart); готовые миры записываются сюда в done, и очередь тоже
 * переживает перезапуск. Выключить: enabled: false в файле.
 *
 * Файл перечитывается при каждом изменении, так что concurrent и список можно менять без
 * перезапуска. Chunky после старта продолжает все свои задачи сразу; лишние сверх
 * concurrent ставятся на паузу (с конца списка) и продолжаются, когда освободится место.
 *
 * Chunky шлёт «задача закончилась» и на паузе тоже. Готовой задача считается только
 * если Chunky записал её с cancelled=true (дошла до конца или отменена). Пауза руками
 * (chunky pause) держит очередь до перезапуска или до chunky continue: новые миры
 * в это время не стартуют. При старте сервера миры, записанные в done, но не
 * доделанные у Chunky (cancelled=false), возвращаются в очередь.
 *
 * 100 % у Chunky ещё не значит «готово»: мир попадает в check, и при следующем старте сервера
 * (свежий процесс, на диске только то, что пережило остановку) RegionCheck читает файлы
 * регионов. В done мир переходит, только если каждый чанк квадрата записан со статусом
 * full; иначе он снова в очереди, и Chunky проходит его заново: готовые чанки пропускает
 * по диску, недостающие догенерирует.
 */
final class PregenQueue {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Path FILE = FMLPaths.CONFIGDIR.get().resolve("notebuns_pregen.json");
    private static final Path TASKS = FMLPaths.CONFIGDIR.get().resolve("chunky").resolve("tasks");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** Первая проверка через 10 с после старта: Chunky уже продолжил свои задачи, лишние уходят на паузу. */
    private static final int FIRST_CHECK_TICKS = 200;
    private static final int CHECK_EVERY_TICKS = 600;

    static final class Settings {
        boolean enabled = true;
        int concurrent = 4;
        double radius = 5000;
        String shape = "square";
        String pattern = "concentric";
        List<String> worlds = new ArrayList<>();
        List<String> done = new ArrayList<>();
        /** Chunky закончил, ждёт проверки файлов регионов при следующем старте. */
        List<String> check = new ArrayList<>();
    }

    private MinecraftServer server;
    private Settings settings;
    private long loadedAt;
    private int ticks;
    private boolean listening;
    /** Миры, которые очередь сама поставила на паузу: их остановка не повод ждать. */
    private final Set<String> selfPaused = new HashSet<>();
    /** Кто-то поставил задачу на паузу руками, и очередь ничего не запускает. */
    private boolean held;

    @SubscribeEvent
    public void onStarted(ServerStartedEvent event) {
        this.server = event.getServer();
        this.settings = load();
        this.loadedAt = 0L; // первая проверка перечитает файл и запишет время
        this.ticks = 0;
        this.listening = false;
        this.held = false;
        this.selfPaused.clear();
        List<String> back = new ArrayList<>();
        for (String world : settings.done) {
            if (Boolean.FALSE.equals(chunkyCancelled(world))) {
                back.add(world);
            }
        }
        for (String world : settings.check) {
            if (Boolean.FALSE.equals(chunkyCancelled(world))) {
                back.add(world);
            }
        }
        if (!back.isEmpty()) {
            settings.done.removeAll(back);
            settings.check.removeAll(back);
            save();
            LOGGER.info("[NoteBuns] прегенерация: не доделаны у Chunky, снова в очереди: {}", back);
        }
        startCheck();
    }

    @SubscribeEvent
    public void onStopping(ServerStoppingEvent event) {
        this.server = null;
    }

    @SubscribeEvent
    public void onTick(ServerTickEvent.Post event) {
        if (server == null || settings == null) {
            return;
        }
        ticks++;
        if (ticks != FIRST_CHECK_TICKS && (ticks < FIRST_CHECK_TICKS || ticks % CHECK_EVERY_TICKS != 0)) {
            return;
        }
        reloadIfChanged();
        if (!settings.enabled) {
            return;
        }
        ChunkyAPI api = api();
        if (api == null) {
            return;
        }
        if (!listening) {
            // Chunky сообщает о конце задачи со своего потока, дальше работаем на главном
            api.onGenerationComplete(done -> {
                MinecraftServer s = server;
                if (s != null) {
                    s.execute(() -> stopped(done.world()));
                }
            });
            listening = true;
        }
        if (held) {
            for (String world : settings.worlds) {
                if (api.isRunning(world)) {
                    held = false; // chunky continue: очередь снова ведёт
                    LOGGER.info("[NoteBuns] прегенерация продолжена, очередь снова работает");
                    break;
                }
            }
            if (held) {
                return;
            }
        }
        fill(api);
    }

    /** Задача мира остановилась: дошла до конца, отменена или на паузе. */
    private void stopped(String world) {
        if (selfPaused.remove(world)) {
            return;
        }
        // 2026-09-24: после падения по памяти сервер завис на выключении, а потоки Chunky
        // «догенерировали» орбиты без сервера (чанки не грузились) и отметили их на 100%.
        // Остановленный сервер выполняет execute() прямо в потоке Chunky, такое не считаем.
        MinecraftServer s = server;
        if (s == null || !s.isRunning() || s.isStopped() || !s.isSameThread()) {
            LOGGER.warn("[NoteBuns] прегенерация {}: Chunky остановил задачу без живого сервера, не засчитано", world);
            return;
        }
        Boolean cancelled = chunkyCancelled(world);
        if (Boolean.FALSE.equals(cancelled)) {
            if (!held) {
                LOGGER.info("[NoteBuns] прегенерация {} на паузе, очередь ждёт перезапуска или chunky continue", world);
            }
            held = true;
            return;
        }
        if (settings.worlds.contains(world) && !settings.done.contains(world) && !settings.check.contains(world)) {
            settings.check.add(world);
            save();
            LOGGER.info("[NoteBuns] прегенерация {}: Chunky закончил, проверка диска при следующем старте сервера", world);
        }
        ChunkyAPI api = api();
        if (api != null && settings.enabled && !held) {
            fill(api);
        }
    }

    /**
     * Держит ровно concurrent задач: лишние (с конца списка) ставит на паузу, а вместо
     * недостающих продолжает приостановленную или начинает новую.
     */
    private void fill(ChunkyAPI api) {
        List<String> active = new ArrayList<>();
        for (String world : settings.worlds) {
            if (api.isRunning(world)) {
                active.add(world);
            }
        }
        for (int i = active.size() - 1; i >= settings.concurrent && i >= 0; i--) {
            String world = active.get(i);
            selfPaused.add(world);
            if (api.pauseTask(world)) {
                LOGGER.info("[NoteBuns] прегенерация {}: на паузе, одновременно не больше {}", world, settings.concurrent);
            } else {
                selfPaused.remove(world);
            }
        }
        int running = Math.min(active.size(), settings.concurrent);
        for (String world : settings.worlds) {
            if (running >= settings.concurrent) {
                return;
            }
            if (settings.done.contains(world) || settings.check.contains(world) || api.isRunning(world)) {
                continue;
            }
            boolean started = api.continueTask(world)
                || api.startTask(world, settings.shape, 0, 0, settings.radius, settings.radius, settings.pattern);
            if (started) {
                running++;
                LOGGER.info("[NoteBuns] прегенерация {}: запущена, работает {} из {}", world, running, settings.concurrent);
            } else {
                LOGGER.warn("[NoteBuns] прегенерация {}: Chunky не принял задачу", world);
            }
        }
    }

    /** Проверка файлов регионов миров из check: в своём потоке, итог на главном. */
    private void startCheck() {
        MinecraftServer s = server;
        if (s == null || !settings.enabled || settings.check.isEmpty()) {
            return;
        }
        if (!"square".equalsIgnoreCase(settings.shape)) {
            LOGGER.warn("[NoteBuns] проверка диска умеет только square, а в очереди {}, поэтому {} не проверены", settings.shape, settings.check);
            return;
        }
        // Chunky берёт чанк, если его центр внутри квадрата; центр очереди 0, 0
        int lo = (int) Math.ceil((-settings.radius - 8) / 16.0);
        int hi = (int) Math.floor((settings.radius - 8) / 16.0);
        Path root = s.getWorldPath(LevelResource.ROOT);
        Map<String, Path> dirs = new LinkedHashMap<>();
        for (String world : settings.check) {
            ResourceLocation id = ResourceLocation.tryParse(world);
            ResourceKey<Level> key = id == null ? null : ResourceKey.create(Registries.DIMENSION, id);
            if (key == null || s.getLevel(key) == null) {
                LOGGER.warn("[NoteBuns] проверка диска {}: мира нет на сервере, пропущен", world);
                continue;
            }
            dirs.put(world, DimensionType.getStorageFolder(key, root).resolve("region"));
        }
        LOGGER.info("[NoteBuns] проверка диска: {} (чанки {}..{})", dirs.keySet(), lo, hi);
        Thread checker = new Thread(() -> {
            for (Map.Entry<String, Path> e : dirs.entrySet()) {
                long started = System.nanoTime();
                RegionCheck.Result r = RegionCheck.scan(e.getValue(), lo, hi, s::isRunning);
                if (r == null) {
                    return; // сервер останавливается, проверим при следующем старте
                }
                long sec = (System.nanoTime() - started) / 1_000_000_000L;
                s.execute(() -> checked(e.getKey(), r, sec));
            }
        }, "NoteBuns Pregen Check");
        checker.setDaemon(true);
        checker.setPriority(Thread.MIN_PRIORITY);
        checker.start();
    }

    private void checked(String world, RegionCheck.Result r, long sec) {
        MinecraftServer s = server;
        // остановленный сервер выполняет execute() в потоке проверки, тогда не трогаем
        if (s == null || !s.isRunning() || !s.isSameThread() || !settings.check.remove(world)) {
            return;
        }
        if (r.missing() == 0) {
            if (!settings.done.contains(world)) {
                settings.done.add(world);
            }
            LOGGER.info("[NoteBuns] проверка диска {}: все {} чанков full, готово ({} из {}), {} с",
                world, r.expected, settings.done.size(), settings.worlds.size(), sec);
        } else {
            LOGGER.warn("[NoteBuns] проверка диска {}: не хватает {} из {} (нет {}, недогенерировано {}, не читается {}), снова в очереди, {} с",
                world, r.missing(), r.expected, r.absent, r.partial, r.unreadable, sec);
        }
        save();
        ChunkyAPI api = api();
        if (api != null && settings.enabled && !held) {
            fill(api);
        }
    }

    /**
     * Как Chunky записал задачу мира: cancelled=true значит дошла до конца или отменена,
     * false значит на паузе, null значит задачи нет. Chunky пишет файл до того, как сообщить
     * об остановке.
     */
    private static Boolean chunkyCancelled(String world) {
        int colon = world.indexOf(':');
        Path file = colon < 0
            ? TASKS.resolve("minecraft").resolve(world + ".properties")
            : TASKS.resolve(world.substring(0, colon)).resolve(world.substring(colon + 1) + ".properties");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (Exception e) {
            return null;
        }
        String v = p.getProperty("cancelled");
        return v == null ? null : Boolean.parseBoolean(v.trim());
    }

    /** Файл правили руками: перечитать; done берётся из файла, он же его и хранит. */
    private void reloadIfChanged() {
        try {
            long modified = Files.exists(FILE) ? Files.getLastModifiedTime(FILE).toMillis() : 0L;
            if (modified != loadedAt) {
                settings = load();
                loadedAt = modified;
                LOGGER.info("[NoteBuns] очередь прегенерации: {} одновременно, готово {} из {}",
                    settings.concurrent, settings.done.size(), settings.worlds.size());
            }
        } catch (Exception e) {
            // не читается, остаёмся на прежних настройках
        }
    }

    private static ChunkyAPI api() {
        try {
            return ChunkyProvider.get().getApi();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static Settings load() {
        try {
            if (Files.exists(FILE)) {
                Settings s = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), Settings.class);
                if (s != null) {
                    return s;
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[NoteBuns] {} не прочитан, очередь выключена", FILE, e);
            Settings off = new Settings();
            off.enabled = false;
            return off;
        }
        Settings empty = new Settings();
        empty.enabled = false; // без файла очередь не работает: список миров задаётся явно
        return empty;
    }

    private void save() {
        try {
            Path tmp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(settings), StandardCharsets.UTF_8);
            Files.move(tmp, FILE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            loadedAt = Files.getLastModifiedTime(FILE).toMillis();
        } catch (Exception e) {
            LOGGER.warn("[NoteBuns] {} не записан", FILE, e);
        }
    }
}

package com.notebuns.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ContainerLog {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final Path ROOT = FMLPaths.GAMEDIR.get().resolve("notebuns").resolve("containerlog");
    private static final Path CONFIG = FMLPaths.CONFIGDIR.get().resolve("notebuns_containerlog.json");
    private static final StackWalker WALKER = StackWalker.getInstance();

    static volatile Settings settings = new Settings();
    static volatile boolean stopping;
    private static ContainerLogWriter writer;
    private static final Map<String, Cause> CAUSES = new ConcurrentHashMap<>();
    private static final ThreadLocal<Boolean> BUSY = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static long configStamp = -1;
    private static long lastConfigCheck;

    static final class Settings {
        boolean enabled = true;
        List<String> excludeBlockEntities = new ArrayList<>(List.of(
                "minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel", "minecraft:shulker_box",
                "minecraft:furnace", "minecraft:blast_furnace", "minecraft:smoker", "minecraft:hopper",
                "minecraft:dropper", "minecraft:dispenser", "minecraft:brewing_stand", "minecraft:crafter",
                "minecraft:chiseled_bookshelf"));
        List<String> trackKeys = new ArrayList<>(List.of(
                "AddressFilter", "Address", "Freq", "Categories", "HiddenCategories", "Connections", "Target",
                "SignAddress", "Filter", "FilterAmount"));
        int saveMinIntervalMinutes = 60;
        boolean logMovedBlocks = false;
        int retentionDays = 30;
        int maxDiskMegabytes = 4096;
        transient Set<String> excludeSet = new HashSet<>();
        transient Set<String> keySet = new HashSet<>();

        void index() {
            excludeSet = new HashSet<>(excludeBlockEntities);
            keySet = new HashSet<>(trackKeys);
        }
    }

    record Cause(String text, long tick) {
    }

    public ContainerLog() {
        loadConfig(true);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            ContainerLogWriter w = writer;
            if (w != null) {
                w.flushSync("stop");
                w.close(15_000);
            }
        }, "NoteBuns-ContainerLog-exit"));
    }

    private static void loadConfig(boolean writeDefault) {
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        try {
            if (!Files.exists(CONFIG)) {
                Settings s = new Settings();
                s.index();
                settings = s;
                if (writeDefault) {
                    Files.createDirectories(CONFIG.getParent());
                    Files.writeString(CONFIG, gson.toJson(s), StandardCharsets.UTF_8);
                }
                configStamp = Files.exists(CONFIG) ? Files.getLastModifiedTime(CONFIG).toMillis() : -1;
                return;
            }
            long stamp = Files.getLastModifiedTime(CONFIG).toMillis();
            if (stamp == configStamp) {
                return;
            }
            Settings s = gson.fromJson(Files.readString(CONFIG, StandardCharsets.UTF_8), Settings.class);
            if (s == null) {
                throw new IllegalStateException("empty config");
            }
            if (s.excludeBlockEntities == null) s.excludeBlockEntities = new Settings().excludeBlockEntities;
            if (s.trackKeys == null) s.trackKeys = new Settings().trackKeys;
            s.index();
            settings = s;
            configStamp = stamp;
            LOGGER.info("[NoteBuns] журнал контейнеров: настройки прочитаны, {}", s.enabled ? "включён" : "выключен");
        } catch (Exception e) {
            LOGGER.warn("[NoteBuns] журнал контейнеров: {} не прочитан ({}), остаются прежние настройки", CONFIG, e.toString());
            if (settings.excludeSet.isEmpty()) {
                settings.index();
            }
        }
    }

    @SubscribeEvent
    public void onStarted(ServerStartedEvent event) {
        stopping = false;
        writer = new ContainerLogWriter(ROOT, settings);
        writer.start();
        LOGGER.info("[NoteBuns] журнал контейнеров: запись в {}, известно контейнеров {}", ROOT, writer.knownCount());
    }

    @SubscribeEvent
    public void onTick(ServerTickEvent.Post event) {
        long now = System.currentTimeMillis();
        if (now - lastConfigCheck > 10_000) {
            lastConfigCheck = now;
            loadConfig(false);
            if (writer != null) {
                writer.settings(settings);
            }
        }
        if (!CAUSES.isEmpty()) {
            long tick = event.getServer().getTickCount();
            CAUSES.values().removeIf(c -> tick - c.tick() > 2);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onStopping(ServerStoppingEvent event) {
        stopping = true;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onStopped(ServerStoppedEvent event) {
        ContainerLogWriter w = writer;
        if (w != null) {
            w.flushSync("stop");
            w.close(30_000);
            writer = null;
        }
    }

    public static void afterStopServer() {
        ContainerLogWriter w = writer;
        if (w != null) {
            w.flushSync("stop");
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onBreak(BlockEvent.BreakEvent event) {
        if (event.isCanceled() || !(event.getLevel() instanceof Level level) || event.getPlayer() == null) {
            return;
        }
        Player p = event.getPlayer();
        CAUSES.put(key(level, event.getPos()), new Cause("player:" + p.getGameProfile().getName() + ":" + p.getUUID(), tick(level)));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onDetonate(ExplosionEvent.Detonate event) {
        Level level = event.getLevel();
        Explosion ex = event.getExplosion();
        StringBuilder sb = new StringBuilder("explosion");
        Entity direct = ex.getDirectSourceEntity();
        Entity indirect = ex.getIndirectSourceEntity();
        if (direct != null) {
            sb.append(':').append(BuiltInRegistries.ENTITY_TYPE.getKey(direct.getType()));
        }
        if (indirect != null && indirect != direct) {
            sb.append(":by:").append(indirect instanceof Player pl ? pl.getGameProfile().getName() : String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(indirect.getType())));
        }
        Cause c = new Cause(sb.toString(), tick(level));
        for (BlockPos pos : event.getAffectedBlocks()) {
            CAUSES.put(key(level, pos), c);
        }
    }

    @SubscribeEvent
    public void onCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("containerlog")
                .requires(s -> s.hasPermission(3))
                .then(Commands.literal("status").executes(ContainerLog::status)));
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        ContainerLogWriter w = writer;
        String text = w == null ? "журнал контейнеров не запущен" : w.status();
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private static long tick(Level level) {
        return level.getServer() != null ? level.getServer().getTickCount() : 0;
    }

    private static String key(Level level, BlockPos pos) {
        return level.dimension().location() + "|" + pos.asLong();
    }

    private static String dimOf(Level level) {
        return level.dimension().location().toString();
    }

    public static void beforeSetBlock(LevelChunk chunk, BlockPos pos, BlockState newState, boolean moving) {
        ContainerLogWriter w = writer;
        Settings s = settings;
        if (w == null || !s.enabled || BUSY.get()) {
            return;
        }
        BlockState old = chunk.getBlockState(pos);
        if (!old.hasBlockEntity() || old.is(newState.getBlock())) {
            return;
        }
        if (moving && !s.logMovedBlocks) {
            return;
        }
        BlockEntity be = chunk.getBlockEntities().get(pos);
        if (be == null || be.isRemoved()) {
            return;
        }
        Level level = chunk.getLevel();
        if (!(level instanceof ServerLevel)) {
            return;
        }
        ResourceLocation typeId = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
        String type = String.valueOf(typeId);
        if (s.excludeSet.contains(type)) {
            return;
        }
        BUSY.set(Boolean.TRUE);
        try {
            CompoundTag tag = be.saveWithFullMetadata(level.registryAccess());
            Scan scan = scan(tag, s);
            if (scan.stacks == 0 && !scan.tracked) {
                return;
            }
            Cause c = CAUSES.remove(key(level, pos));
            String ev;
            String by;
            if (c != null) {
                ev = c.text().startsWith("player:") ? "break" : "explode";
                by = c.text();
            } else {
                ev = moving ? "moved" : "replace";
                by = caller();
            }
            String block = String.valueOf(BuiltInRegistries.BLOCK.getKey(old.getBlock())) + " -> " + BuiltInRegistries.BLOCK.getKey(newState.getBlock());
            w.submit(ContainerLogWriter.Entry.of(ev, dimOf(level), pos.getX(), pos.getY(), pos.getZ(), type, block, by, scan, tag));
        } catch (Throwable t) {
            LOGGER.warn("[NoteBuns] журнал контейнеров: снимок {} {} не снят: {}", dimOf(level), pos.toShortString(), t.toString());
        } finally {
            BUSY.set(Boolean.FALSE);
        }
    }

    public static void beforeCommandClear(Object target) {
        ContainerLogWriter w = writer;
        Settings s = settings;
        if (w == null || !s.enabled || !(target instanceof BlockEntity be) || !(be.getLevel() instanceof ServerLevel level) || be.isRemoved()) {
            return;
        }
        String type = String.valueOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()));
        if (s.excludeSet.contains(type)) {
            return;
        }
        BlockPos pos = be.getBlockPos();
        try {
            CompoundTag tag = be.saveWithFullMetadata(level.registryAccess());
            Scan scan = scan(tag, s);
            if (scan.stacks == 0 && !scan.tracked) {
                return;
            }
            String block = String.valueOf(BuiltInRegistries.BLOCK.getKey(be.getBlockState().getBlock())) + " -> cleared";
            w.submit(ContainerLogWriter.Entry.of("replace", dimOf(level), pos.getX(), pos.getY(), pos.getZ(), type, block, "command", scan, tag));
        } catch (Throwable t) {
            LOGGER.warn("[NoteBuns] журнал контейнеров: снимок {} {} не снят: {}", dimOf(level), pos.toShortString(), t.toString());
        }
    }

    public static void afterChunkWrite(ServerLevel level, ChunkAccess chunk, CompoundTag chunkTag) {
        ContainerLogWriter w = writer;
        Settings s = settings;
        if (w == null || !s.enabled || chunkTag == null) {
            return;
        }
        try {
            ListTag list = chunkTag.getList("block_entities", Tag.TAG_COMPOUND);
            String dim = dimOf(level);
            Set<Long> present = new HashSet<>();
            long now = System.currentTimeMillis();
            long minGap = s.saveMinIntervalMinutes * 60_000L;
            for (int i = 0; i < list.size(); i++) {
                CompoundTag be = list.getCompound(i);
                String type = be.getString("id");
                if (type.isEmpty() || s.excludeSet.contains(type)) {
                    continue;
                }
                int x = be.getInt("x"), y = be.getInt("y"), z = be.getInt("z");
                long pk = BlockPos.asLong(x, y, z);
                Scan scan = scan(be, s);
                if (scan.stacks == 0 && !scan.tracked) {
                    continue;
                }
                present.add(pk);
                ContainerLogWriter.Known k = w.known(dim, pk);
                if (k != null && k.hash().equals(scan.hash)) {
                    continue;
                }
                if (k != null && !stopping && now - k.time() < minGap) {
                    w.defer(ContainerLogWriter.Entry.of("change", dim, x, y, z, type, null, "save", scan, be.copy()));
                    continue;
                }
                w.submit(ContainerLogWriter.Entry.of(k == null ? "save" : "change", dim, x, y, z, type, null, stopping ? "stop" : "save", scan, be.copy()));
            }
            String status = chunkTag.getString("Status");
            if (status.equals("minecraft:full") || status.equals("full")) {
                w.forgetMissing(dim, chunk.getPos().x, chunk.getPos().z, present, stopping);
            }
        } catch (Throwable t) {
            LOGGER.warn("[NoteBuns] журнал контейнеров: сохранение чанка {} {} не разобрано: {}", dimOf(level), chunk.getPos(), t.toString());
        }
    }

    private static String caller() {
        boolean command = WALKER.walk(frames -> frames.anyMatch(f -> f.getClassName().startsWith("net.minecraft.server.commands.")
                || f.getClassName().startsWith("org.bukkit.command.") || f.getClassName().startsWith("com.mojang.brigadier.")));
        if (command) {
            return "command";
        }
        return WALKER.walk(frames -> frames
                .map(f -> f.getClassName())
                .filter(n -> !n.startsWith("net.minecraft.") && !n.startsWith("net.neoforged.") && !n.startsWith("java.")
                        && !n.startsWith("jdk.") && !n.startsWith("sun.") && !n.startsWith("com.notebuns.server")
                        && !n.startsWith("org.bukkit.craftbukkit") && !n.startsWith("com.mohistmc.") && !n.startsWith("org.spongepowered.")
                        && !n.startsWith("cpw.") && !n.startsWith("com.mojang.") && !n.startsWith("org.bukkit."))
                .findFirst()
                .map(n -> "code:" + n)
                .orElse("other"));
    }

    static final class Scan {
        int stacks;
        long items;
        boolean tracked;
        String hash;
        String summary;
    }

    static Scan scan(CompoundTag be, Settings s) {
        Scan r = new Scan();
        Map<String, Long> totals = new LinkedHashMap<>();
        StringBuilder canon = new StringBuilder();
        for (String key : be.getAllKeys()) {
            if (key.equals("id") || key.equals("x") || key.equals("y") || key.equals("z") || key.equals("components")) {
                continue;
            }
            Tag t = be.get(key);
            if (s.keySet.contains(key)) {
                if (meaningful(t)) {
                    r.tracked = true;
                }
                canon.append('#').append(key).append('=').append(t).append('\n');
            }
            walk(t, r, totals, canon);
        }
        r.hash = sha(canon.toString());
        StringBuilder sum = new StringBuilder();
        int n = 0;
        for (Map.Entry<String, Long> e : totals.entrySet()) {
            if (n++ == 12) {
                sum.append(" …");
                break;
            }
            if (sum.length() > 0) sum.append(", ");
            sum.append(e.getKey()).append('×').append(e.getValue());
        }
        r.summary = sum.toString();
        return r;
    }

    private static boolean meaningful(Tag t) {
        if (t instanceof CompoundTag c) return !c.isEmpty();
        if (t instanceof ListTag l) return !l.isEmpty();
        if (t instanceof StringTag st) return !st.getAsString().isEmpty();
        if (t instanceof IntArrayTag ia) return ia.size() > 0;
        return false;
    }

    private static void walk(Tag t, Scan r, Map<String, Long> totals, StringBuilder canon) {
        if (t instanceof CompoundTag c) {
            Tag id = c.get("id");
            Tag cnt = c.contains("count") ? c.get("count") : c.get("Count");
            if (id instanceof StringTag sid && cnt instanceof NumericTag num && !sid.getAsString().isEmpty() && !sid.getAsString().equals("minecraft:air")) {
                long count = num.getAsLong();
                r.stacks++;
                r.items += count;
                totals.merge(sid.getAsString(), count, Long::sum);
                canon.append(c).append('\n');
                Tag comps = c.get("components");
                if (comps != null) {
                    walk(comps, r, totals, new StringBuilder());
                }
                return;
            }
            for (String k : c.getAllKeys()) {
                walk(c.get(k), r, totals, canon);
            }
        } else if (t instanceof ListTag l) {
            for (Tag e : l) {
                walk(e, r, totals, canon);
            }
        }
    }

    private static String sha(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 10; i++) b.append(String.format("%02x", d[i]));
            return b.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

}

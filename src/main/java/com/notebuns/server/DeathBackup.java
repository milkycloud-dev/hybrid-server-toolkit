package com.notebuns.server;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Полный снимок игрока в момент смерти, включая модовые слоты.
 *
 * InventoryRollbackPlus пишет смерть из Bukkit PlayerDeathEvent и видит только то, что
 * знает Bukkit: 36 слотов, броню, вторую руку и эндер-сундук. Слоты Curios, Accessories
 * (кольца Aether, спина с рюкзаком Traveler's Backpack), рюкзаки внутри них и прочее
 * лежат во вложениях NeoForge (neoforge:attachments) и в бэкап IRP не попадают.
 *
 * Здесь сохраняются все данные игрока целиком (saveWithoutId): ванильный инвентарь,
 * эндер-сундук, все вложения модов, опыт, эффекты. LivingDeathEvent в Youer идёт до
 * PlayerDeathEvent и до выпадения предметов, поэтому слоты ещё полные. Снимок берётся
 * на HIGHEST, а пишется на LOWEST, только если смерть не отменили (Hardcore Revival
 * отменяет её на High, когда кладёт игрока в нокаут).
 *
 * Файлы: notebuns/deaths/<uuid>/<время МСК>.dat (gzip NBT), последние KEEP на игрока.
 * Команды (уровень 3): /deathbackup list <ник>, /deathbackup view <ник> <№> [mods|inventory|ender] [стр].
 * view открывает копии предметов в сундуке, как GUI IRP.
 */
final class DeathBackup {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Path ROOT = FMLPaths.GAMEDIR.get().resolve("notebuns").resolve("deaths");
    private static final int KEEP = 60;
    private static final int LIST = 15;
    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter FILE = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(MSK);
    private static final DateTimeFormatter SHOW = DateTimeFormatter.ofPattern("dd.MM HH:mm:ss").withZone(MSK);
    /** Ключи игрока, где предметы не модовые или чужие (ездовое и плечевое существо). */
    private static final Set<String> NOT_MODS = Set.of("Inventory", "EnderItems", "RootVehicle",
        "ShoulderEntityLeft", "ShoulderEntityRight");
    private static final List<String> SECTIONS = List.of("mods", "inventory", "ender");

    private final Map<UUID, CompoundTag> pending = new HashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "NoteBuns-DeathBackup");
        t.setDaemon(true);
        return t;
    });

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void capture(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        try {
            CompoundTag meta = new CompoundTag();
            meta.putLong("time", System.currentTimeMillis());
            meta.putString("name", player.getGameProfile().getName());
            meta.putString("dimension", player.level().dimension().location().toString());
            meta.putDouble("x", player.getX());
            meta.putDouble("y", player.getY());
            meta.putDouble("z", player.getZ());
            DamageSource source = event.getSource();
            meta.putString("cause", source.getMsgId());
            Entity attacker = source.getEntity();
            if (attacker != null) {
                meta.putString("attacker", BuiltInRegistries.ENTITY_TYPE.getKey(attacker.getType()).toString());
            }
            CompoundTag snap = new CompoundTag();
            snap.put("meta", meta);
            snap.put("player", player.saveWithoutId(new CompoundTag()));
            pending.put(player.getUUID(), snap);
        } catch (RuntimeException e) {
            LOGGER.warn("[NoteBuns] снимок смерти {} не снят", player.getGameProfile().getName(), e);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void commit(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        CompoundTag snap = pending.remove(player.getUUID());
        if (snap == null || event.isCanceled()) {
            return;
        }
        Path dir = ROOT.resolve(player.getUUID().toString());
        String name = FILE.format(Instant.ofEpochMilli(snap.getCompound("meta").getLong("time"))) + ".dat";
        io.execute(() -> write(dir, name, snap));
    }

    @SubscribeEvent
    public void onStopping(ServerStoppingEvent event) {
        io.shutdown();
        try {
            io.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void write(Path dir, String name, CompoundTag snap) {
        try {
            Files.createDirectories(dir);
            Path tmp = dir.resolve(name + ".tmp");
            NbtIo.writeCompressed(snap, tmp);
            Files.move(tmp, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            List<Path> files = files(dir);
            for (int i = KEEP; i < files.size(); i++) {
                Files.deleteIfExists(files.get(i));
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[NoteBuns] снимок смерти {} не записан", dir.resolve(name), e);
        }
    }

    /** Снимки игрока, новые первыми. */
    private static List<Path> files(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".dat"))
                .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()))
                .toList();
        }
    }

    // ---------- команды ----------

    @SubscribeEvent
    public void onCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("deathbackup")
            .requires(s -> s.hasPermission(3))
            .then(Commands.literal("list")
                .then(Commands.argument("player", StringArgumentType.word())
                    .executes(this::list)))
            .then(Commands.literal("view")
                .then(Commands.argument("player", StringArgumentType.word())
                    .then(Commands.argument("n", IntegerArgumentType.integer(1))
                        .executes(c -> view(c, "mods", 1))
                        .then(Commands.argument("section", StringArgumentType.word())
                            .suggests((c, b) -> {
                                SECTIONS.forEach(b::suggest);
                                return b.buildFuture();
                            })
                            .executes(c -> view(c, StringArgumentType.getString(c, "section"), 1))
                            .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(c -> view(c, StringArgumentType.getString(c, "section"),
                                    IntegerArgumentType.getInteger(c, "page")))))))));
    }

    private int list(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        String nick = StringArgumentType.getString(c, "player");
        Optional<UUID> uuid = resolve(src.getServer(), nick);
        if (uuid.isEmpty()) {
            src.sendFailure(Component.literal("Нет снимков смертей для " + nick));
            return 0;
        }
        try {
            List<Path> files = files(ROOT.resolve(uuid.get().toString()));
            if (files.isEmpty()) {
                src.sendFailure(Component.literal("Нет снимков смертей для " + nick));
                return 0;
            }
            src.sendSuccess(() -> Component.literal("Смерти " + nick + " (всего " + files.size() + ", новые сверху):"), false);
            for (int i = 0; i < Math.min(LIST, files.size()); i++) {
                CompoundTag snap = NbtIo.readCompressed(files.get(i), NbtAccounter.unlimitedHeap());
                String line = "#" + (i + 1) + " " + describe(src.getServer(), snap);
                src.sendSuccess(() -> Component.literal(line), false);
            }
            return files.size();
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[NoteBuns] deathbackup list {}", nick, e);
            src.sendFailure(Component.literal("Не прочитать снимки: " + e.getMessage()));
            return 0;
        }
    }

    private int view(CommandContext<CommandSourceStack> c, String section, int page) {
        CommandSourceStack src = c.getSource();
        String nick = StringArgumentType.getString(c, "player");
        int n = IntegerArgumentType.getInteger(c, "n");
        if (!SECTIONS.contains(section)) {
            src.sendFailure(Component.literal("Раздел: mods, inventory или ender"));
            return 0;
        }
        ServerPlayer viewer = src.getPlayer();
        if (viewer == null) {
            src.sendFailure(Component.literal("Открыть может только игрок"));
            return 0;
        }
        Optional<UUID> uuid = resolve(src.getServer(), nick);
        try {
            List<Path> files = uuid.isPresent() ? files(ROOT.resolve(uuid.get().toString())) : List.of();
            if (n > files.size()) {
                src.sendFailure(Component.literal("Снимка #" + n + " нет, всего " + files.size()));
                return 0;
            }
            CompoundTag snap = NbtIo.readCompressed(files.get(n - 1), NbtAccounter.unlimitedHeap());
            List<ItemStack> stacks = new ArrayList<>(items(src.getServer(), snap, section).values().stream()
                .flatMap(List::stream).toList());
            int pages = Math.max(1, (stacks.size() + 53) / 54);
            if (page > pages) {
                src.sendFailure(Component.literal("Страниц " + pages));
                return 0;
            }
            SimpleContainer box = new SimpleContainer(54);
            for (int i = (page - 1) * 54, slot = 0; i < stacks.size() && slot < 54; i++, slot++) {
                box.setItem(slot, stacks.get(i));
            }
            Component title = Component.literal(nick + " #" + n + " " + section + " " + page + "/" + pages);
            viewer.openMenu(new SimpleMenuProvider((id, inv, p) -> ChestMenu.sixRows(id, inv, box), title));
            return stacks.size();
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[NoteBuns] deathbackup view {} #{}", nick, n, e);
            src.sendFailure(Component.literal("Не прочитать снимок: " + e.getMessage()));
            return 0;
        }
    }

    private static String describe(MinecraftServer server, CompoundTag snap) {
        CompoundTag meta = snap.getCompound("meta");
        StringBuilder sb = new StringBuilder();
        sb.append(SHOW.format(Instant.ofEpochMilli(meta.getLong("time")))).append(' ')
            .append(meta.getString("dimension")).append(' ')
            .append((int) Math.floor(meta.getDouble("x"))).append(' ')
            .append((int) Math.floor(meta.getDouble("y"))).append(' ')
            .append((int) Math.floor(meta.getDouble("z"))).append(' ')
            .append(meta.getString("cause"));
        if (meta.contains("attacker")) {
            sb.append(" (").append(meta.getString("attacker")).append(')');
        }
        sb.append(" | инвентарь ").append(count(items(server, snap, "inventory")))
            .append(", эндер ").append(count(items(server, snap, "ender")));
        Map<String, List<ItemStack>> mods = items(server, snap, "mods");
        sb.append(", моды ").append(count(mods));
        if (!mods.isEmpty()) {
            sb.append(' ');
            List<String> parts = new ArrayList<>();
            mods.forEach((k, v) -> parts.add(k + " " + v.size()));
            sb.append(parts);
        }
        return sb.toString();
    }

    private static int count(Map<String, List<ItemStack>> m) {
        return m.values().stream().mapToInt(List::size).sum();
    }

    /** Предметы раздела снимка, сгруппированные по источнику (вложение мода и т.п.). */
    private static Map<String, List<ItemStack>> items(MinecraftServer server, CompoundTag snap, String section) {
        CompoundTag player = snap.getCompound("player");
        Map<String, List<ItemStack>> out = new LinkedHashMap<>();
        switch (section) {
            case "inventory" -> collect(server, player.get("Inventory"), "inventory", out);
            case "ender" -> collect(server, player.get("EnderItems"), "ender", out);
            default -> {
                for (String key : player.getAllKeys()) {
                    if (NOT_MODS.contains(key)) {
                        continue;
                    }
                    if (key.equals("neoforge:attachments")) {
                        CompoundTag att = player.getCompound(key);
                        for (String id : att.getAllKeys()) {
                            collect(server, att.get(id), id, out);
                        }
                    } else {
                        collect(server, player.get(key), key, out);
                    }
                }
            }
        }
        return out;
    }

    /** Любой {id: предмет, count: N} в дереве NBT; внутрь найденного предмета не спускаемся (его содержимое едет с ним). */
    private static void collect(MinecraftServer server, Tag tag, String label, Map<String, List<ItemStack>> out) {
        if (tag instanceof CompoundTag ct) {
            if (isItem(ct)) {
                ItemStack.parse(server.registryAccess(), ct).filter(s -> !s.isEmpty())
                    .ifPresent(s -> out.computeIfAbsent(label, k -> new ArrayList<>()).add(s));
                return;
            }
            for (String k : ct.getAllKeys()) {
                collect(server, ct.get(k), label, out);
            }
        } else if (tag instanceof ListTag lt) {
            for (Tag t : lt) {
                collect(server, t, label, out);
            }
        }
    }

    private static boolean isItem(CompoundTag ct) {
        if (!ct.contains("id", Tag.TAG_STRING) || !(ct.contains("count", Tag.TAG_ANY_NUMERIC) || ct.contains("Count", Tag.TAG_ANY_NUMERIC))) {
            return false;
        }
        ResourceLocation id = ResourceLocation.tryParse(ct.getString("id"));
        return id != null && BuiltInRegistries.ITEM.containsKey(id);
    }

    private static Optional<UUID> resolve(MinecraftServer server, String nick) {
        ServerPlayer online = server.getPlayerList().getPlayerByName(nick);
        if (online != null) {
            return Optional.of(online.getUUID());
        }
        // В offline-режиме кэш профилей выдаёт UUID и для незнакомого ника (по точному написанию),
        // поэтому берём его, только если под ним есть снимки.
        if (server.getProfileCache() != null) {
            Optional<GameProfile> profile = server.getProfileCache().get(nick);
            if (profile.isPresent() && Files.isDirectory(ROOT.resolve(profile.get().getId().toString()))) {
                return Optional.of(profile.get().getId());
            }
        }
        // Иначе ищем по имени в последнем снимке каждой папки (без учёта регистра).
        if (!Files.isDirectory(ROOT)) {
            return Optional.empty();
        }
        List<Path> dirs;
        try (Stream<Path> s = Files.list(ROOT)) {
            dirs = s.toList();
        } catch (IOException e) {
            LOGGER.warn("[NoteBuns] deathbackup: список папок", e);
            return Optional.empty();
        }
        for (Path dir : dirs) {
            try {
                List<Path> f = files(dir);
                if (!f.isEmpty() && nick.equalsIgnoreCase(
                        NbtIo.readCompressed(f.get(0), NbtAccounter.unlimitedHeap()).getCompound("meta").getString("name"))) {
                    return Optional.of(UUID.fromString(dir.getFileName().toString()));
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("[NoteBuns] deathbackup: снимок в {} не прочитан", dir, e);
            }
        }
        return Optional.empty();
    }
}

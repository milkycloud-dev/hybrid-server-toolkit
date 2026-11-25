package com.notebuns.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import org.slf4j.Logger;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

final class ContainerLogWriter implements Runnable {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(MSK);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").withZone(MSK);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Entry STOP = new Entry(null, null, 0, 0, 0, null, null, null, null, null, 0);

    record Known(String hash, long time) {
    }

    record Entry(String ev, String dim, int x, int y, int z, String type, String block, String by, ContainerLog.Scan scan, CompoundTag nbt, long time) {
        static Entry of(String ev, String dim, int x, int y, int z, String type, String block, String by, ContainerLog.Scan scan, CompoundTag nbt) {
            return new Entry(ev, dim, x, y, z, type, block, by, scan, nbt, System.currentTimeMillis());
        }
    }

    private final Path root;
    private volatile ContainerLog.Settings settings;
    private final LinkedBlockingQueue<Entry> queue = new LinkedBlockingQueue<>(200_000);
    private final Map<String, Map<Long, Known>> known = new ConcurrentHashMap<>();
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private volatile long lastWrite;
    private volatile String lastError = "";
    private Thread thread;
    private BufferedWriter out;
    private String outDay;
    private long lastIndexSave;
    private long lastHousekeep;
    private volatile boolean indexDirty;
    private final Object io = new Object();
    private final Map<String, Map<Long, Entry>> pending = new ConcurrentHashMap<>();

    ContainerLogWriter(Path root, ContainerLog.Settings settings) {
        this.root = root;
        this.settings = settings;
        loadIndex();
    }

    void settings(ContainerLog.Settings s) {
        this.settings = s;
    }

    void start() {
        thread = new Thread(this, "NoteBuns-ContainerLog");
        thread.setDaemon(true);
        thread.start();
    }

    int knownCount() {
        int n = 0;
        for (Map<Long, Known> m : known.values()) n += m.size();
        return n;
    }

    Known known(String dim, long pos) {
        Map<Long, Known> m = known.get(dim);
        return m == null ? null : m.get(pos);
    }

    void submit(Entry e) {
        long pk = BlockPos.asLong(e.x(), e.y(), e.z());
        if (e.ev().equals("save") || e.ev().equals("change")) {
            known.computeIfAbsent(e.dim(), d -> new ConcurrentHashMap<>()).put(pk, new Known(e.scan().hash, e.time()));
            indexDirty = true;
        } else {
            Map<Long, Known> m = known.get(e.dim());
            if (m != null && m.remove(pk) != null) {
                indexDirty = true;
            }
        }
        Map<Long, Entry> pm = pending.get(e.dim());
        if (pm != null) {
            pm.remove(pk);
        }
        if (!queue.offer(e)) {
            dropped.incrementAndGet();
        }
    }

    void defer(Entry e) {
        pending.computeIfAbsent(e.dim(), d -> new ConcurrentHashMap<>()).put(BlockPos.asLong(e.x(), e.y(), e.z()), e);
    }

    int pendingCount() {
        int n = 0;
        for (Map<Long, Entry> m : pending.values()) n += m.size();
        return n;
    }

    private void releasePending(long now, boolean all, String by) {
        long gap = (long) settings.saveMinIntervalMinutes * 60_000L;
        for (Map.Entry<String, Map<Long, Entry>> d : pending.entrySet()) {
            for (Map.Entry<Long, Entry> pe : d.getValue().entrySet()) {
                Known k = known(d.getKey(), pe.getKey());
                if (all || k == null || now - k.time() >= gap) {
                    Entry e = pe.getValue();
                    if (d.getValue().remove(pe.getKey(), e)) {
                        submit(new Entry("change", e.dim(), e.x(), e.y(), e.z(), e.type(), e.block(), by == null ? e.by() : by, e.scan(), e.nbt(), all ? now : e.time()));
                    }
                }
            }
        }
    }

    void flushSync(String by) {
        synchronized (io) {
            long now = System.currentTimeMillis();
            releasePending(now, true, by);
            List<Entry> batch = new ArrayList<>();
            queue.drainTo(batch);
            boolean stop = false;
            for (Entry e : batch) {
                if (e == STOP) {
                    stop = true;
                } else {
                    write(e);
                }
            }
            flush();
            saveIndex();
            if (stop) {
                queue.offer(STOP);
            }
        }
    }

    void forgetMissing(String dim, int cx, int cz, Set<Long> present, boolean stopping) {
        Map<Long, Known> m = known.get(dim);
        if (m == null) {
            return;
        }
        List<Long> gone = new ArrayList<>();
        for (Long pk : m.keySet()) {
            int x = BlockPos.getX(pk), z = BlockPos.getZ(pk);
            if ((x >> 4) == cx && (z >> 4) == cz && !present.contains(pk)) {
                gone.add(pk);
            }
        }
        Map<Long, Entry> pm = pending.get(dim);
        for (Long pk : gone) {
            m.remove(pk);
            if (pm != null) {
                pm.remove(pk);
            }
            indexDirty = true;
            ContainerLog.Scan s = new ContainerLog.Scan();
            s.hash = "";
            s.summary = "";
            if (!queue.offer(new Entry("empty", dim, BlockPos.getX(pk), BlockPos.getY(pk), BlockPos.getZ(pk), null, null, stopping ? "stop" : "save", s, null, System.currentTimeMillis()))) {
                dropped.incrementAndGet();
            }
        }
    }

    String status() {
        return "журнал контейнеров: записей " + written.get() + ", в очереди " + queue.size() + ", потеряно " + dropped.get()
                + ", известно контейнеров " + knownCount() + ", отложено " + pendingCount() + ", последняя запись " + (lastWrite == 0 ? "нет" : TS.format(Instant.ofEpochMilli(lastWrite)))
                + (lastError.isEmpty() ? "" : ", ошибка: " + lastError);
    }

    void close(long waitMillis) {
        if (thread == null || !thread.isAlive()) {
            return;
        }
        queue.offer(STOP);
        try {
            thread.join(waitMillis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void run() {
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            lastError = e.toString();
        }
        housekeep(true);
        while (true) {
            Entry e;
            try {
                e = queue.poll(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                break;
            }
            long now = System.currentTimeMillis();
            if (e == STOP) {
                flushSync(null);
                synchronized (io) {
                    closeOut();
                }
                LOGGER.info("[NoteBuns] журнал контейнеров: остановлен, записей {}, потеряно {}, в индексе контейнеров {}", written.get(), dropped.get(), knownCount());
                return;
            }
            if (e != null) {
                synchronized (io) {
                    write(e);
                    drainAndWrite(now);
                }
            }
            if (!ContainerLog.stopping) {
                releasePending(now, false, null);
            }
            if (indexDirty && (ContainerLog.stopping || now - lastIndexSave > 120_000)) {
                synchronized (io) {
                    saveIndex();
                }
            }
            if (now - lastHousekeep > 3_600_000) {
                housekeep(false);
            }
        }
    }

    private void drainAndWrite(long now) {
        List<Entry> batch = new ArrayList<>();
        queue.drainTo(batch, 5000);
        for (Entry e : batch) {
            if (e != STOP) {
                write(e);
            } else {
                queue.offer(STOP);
                break;
            }
        }
        flush();
    }

    private void write(Entry e) {
        try {
            String day = DAY.format(Instant.ofEpochMilli(e.time()));
            if (!day.equals(outDay)) {
                closeOut();
                out = Files.newBufferedWriter(root.resolve(day + ".jsonl"), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                outDay = day;
                compressOldDays(day);
            }
            JsonObject o = new JsonObject();
            o.addProperty("t", TS.format(Instant.ofEpochMilli(e.time())));
            o.addProperty("ev", e.ev());
            o.addProperty("dim", e.dim());
            o.addProperty("x", e.x());
            o.addProperty("y", e.y());
            o.addProperty("z", e.z());
            if (e.type() != null) o.addProperty("be", e.type());
            if (e.block() != null) o.addProperty("block", e.block());
            o.addProperty("by", e.by());
            if (e.scan() != null && e.nbt() != null) {
                o.addProperty("stacks", e.scan().stacks);
                o.addProperty("items", e.scan().items);
                o.addProperty("hash", e.scan().hash);
                o.addProperty("sum", e.scan().summary);
                o.addProperty("nbt", encode(e.nbt()));
            }
            out.write(GSON.toJson(o));
            out.write('\n');
            written.incrementAndGet();
            lastWrite = e.time();
        } catch (Exception ex) {
            lastError = ex.toString();
            dropped.incrementAndGet();
            LOGGER.warn("[NoteBuns] журнал контейнеров: запись не удалась: {}", ex.toString());
            closeOut();
        }
    }

    private static String encode(CompoundTag tag) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        NbtIo.writeCompressed(tag, bos);
        return Base64.getEncoder().encodeToString(bos.toByteArray());
    }

    private void flush() {
        try {
            if (out != null) out.flush();
        } catch (IOException ex) {
            lastError = ex.toString();
            closeOut();
        }
    }

    private void closeOut() {
        try {
            if (out != null) out.close();
        } catch (IOException ignored) {
        }
        out = null;
        outDay = null;
    }

    private void compressOldDays(String today) {
        try (Stream<Path> s = Files.list(root)) {
            for (Path p : s.filter(f -> f.getFileName().toString().endsWith(".jsonl")).toList()) {
                String name = p.getFileName().toString();
                if (name.startsWith(today)) continue;
                Path gz = root.resolve(name + ".gz");
                Path tmp = root.resolve(name + ".gz.tmp");
                try (InputStream in = Files.newInputStream(p); OutputStream o = new GZIPOutputStream(Files.newOutputStream(tmp))) {
                    in.transferTo(o);
                }
                Files.move(tmp, gz, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                Files.delete(p);
            }
        } catch (IOException ex) {
            lastError = ex.toString();
        }
    }

    private void housekeep(boolean first) {
        lastHousekeep = System.currentTimeMillis();
        ContainerLog.Settings s = settings;
        try (Stream<Path> st = Files.list(root)) {
            List<Path> files = new ArrayList<>(st.filter(f -> f.getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}\\.jsonl(\\.gz)?")).sorted(Comparator.comparing(p -> p.getFileName().toString())).toList());
            LocalDate cutoff = LocalDate.now(MSK).minusDays(Math.max(1, s.retentionDays));
            long total = 0;
            for (Path p : files) total += Files.size(p);
            long limit = (long) Math.max(64, s.maxDiskMegabytes) * 1024 * 1024;
            String today = DAY.format(Instant.now());
            for (Path p : files) {
                String name = p.getFileName().toString();
                if (name.startsWith(today)) continue;
                LocalDate d = LocalDate.parse(name.substring(0, 10));
                if (d.isBefore(cutoff) || total > limit) {
                    total -= Files.size(p);
                    Files.delete(p);
                    LOGGER.info("[NoteBuns] журнал контейнеров: удалён старый файл {}", name);
                }
            }
            if (first) {
                compressOldDays(today);
            }
        } catch (Exception ex) {
            lastError = ex.toString();
        }
    }

    private void loadIndex() {
        Path p = root.resolve("index.dat");
        if (!Files.exists(p)) {
            return;
        }
        try {
            CompoundTag tag = NbtIo.readCompressed(p, NbtAccounter.unlimitedHeap());
            for (String dim : tag.getAllKeys()) {
                ListTag list = tag.getList(dim, Tag.TAG_COMPOUND);
                Map<Long, Known> m = known.computeIfAbsent(dim, d -> new ConcurrentHashMap<>());
                for (int i = 0; i < list.size(); i++) {
                    CompoundTag c = list.getCompound(i);
                    m.put(c.getLong("p"), new Known(c.getString("h"), c.getLong("t")));
                }
            }
        } catch (Exception ex) {
            LOGGER.warn("[NoteBuns] журнал контейнеров: index.dat не прочитан ({}), начнётся с нуля", ex.toString());
        }
    }

    private void saveIndex() {
        lastIndexSave = System.currentTimeMillis();
        indexDirty = false;
        CompoundTag tag = new CompoundTag();
        for (Map.Entry<String, Map<Long, Known>> d : known.entrySet()) {
            ListTag list = new ListTag();
            for (Map.Entry<Long, Known> e : d.getValue().entrySet()) {
                CompoundTag c = new CompoundTag();
                c.putLong("p", e.getKey());
                c.putString("h", e.getValue().hash());
                c.putLong("t", e.getValue().time());
                list.add(c);
            }
            tag.put(d.getKey(), list);
        }
        try {
            Files.createDirectories(root);
            Path tmp = root.resolve("index.dat.tmp");
            NbtIo.writeCompressed(tag, tmp);
            Files.move(tmp, root.resolve("index.dat"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            lastError = ex.toString();
        }
    }
}

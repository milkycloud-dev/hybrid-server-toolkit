package com.notebuns.server;

import net.minecraft.world.level.chunk.storage.RegionFileVersion;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.BooleanSupplier;

/**
 * Готов ли мир на самом деле: каждый чанк квадрата прегенерации лежит в файлах регионов
 * со статусом full.
 *
 * Chunky этого не гарантирует. 2026-09-24: glacio и mercury_orbit стояли у Chunky на 100 %,
 * а на диске лежали сотни чанков в structure_starts / biomes / carvers / initialize_light.
 * Chunky засчитывает чанк, даже если загрузка кончилась ошибкой, и пишет прогресс в свой
 * файл до сохранения миров: процесс, убитый во время «Сохранение миров», теряет чанки,
 * которые Chunky уже считает сделанными.
 *
 * Файлы только читаются: заголовок региона и начало данных чанка: Status в NBT чанка
 * идёт первым полем, дальше него не распаковываем.
 */
final class RegionCheck {
    static final class Result {
        long expected;
        long full;
        long absent;
        long partial;
        long unreadable;

        long missing() {
            return expected - full;
        }
    }

    private RegionCheck() {
    }

    /** Чанки lo..hi по обеим осям; null, если проверку прервали (сервер останавливается). */
    static Result scan(Path regionDir, int lo, int hi, BooleanSupplier alive) {
        Result r = new Result();
        long side = hi - lo + 1L;
        r.expected = side * side;
        for (int rx = Math.floorDiv(lo, 32); rx <= Math.floorDiv(hi, 32); rx++) {
            for (int rz = Math.floorDiv(lo, 32); rz <= Math.floorDiv(hi, 32); rz++) {
                if (!alive.getAsBoolean()) {
                    return null;
                }
                region(regionDir, rx, rz, lo, hi, r);
            }
        }
        return r;
    }

    private static void region(Path dir, int rx, int rz, int lo, int hi, Result r) {
        int inside = 0;
        for (int i = 0; i < 1024; i++) {
            if (inside(rx * 32 + (i & 31), rz * 32 + (i >> 5), lo, hi)) {
                inside++;
            }
        }
        Path file = dir.resolve("r." + rx + "." + rz + ".mca");
        if (!Files.isRegularFile(file)) {
            r.absent += inside;
            return;
        }
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer header = ByteBuffer.allocate(4096);
            if (!readFully(ch, header, 0L)) {
                r.unreadable += inside;
                return;
            }
            for (int i = 0; i < 1024; i++) {
                int cx = rx * 32 + (i & 31);
                int cz = rz * 32 + (i >> 5);
                if (!inside(cx, cz, lo, hi)) {
                    continue;
                }
                int sector = header.getInt(i * 4) >>> 8;
                if (sector == 0) {
                    r.absent++;
                    continue;
                }
                String status;
                try {
                    status = status(dir, ch, sector * 4096L, cx, cz);
                } catch (IOException | RuntimeException e) {
                    status = null;
                }
                if (status == null) {
                    r.unreadable++;
                } else if ("minecraft:full".equals(status) || "full".equals(status)) {
                    r.full++;
                } else {
                    r.partial++;
                }
            }
        } catch (IOException e) {
            r.unreadable += inside;
        }
    }

    private static boolean inside(int cx, int cz, int lo, int hi) {
        return cx >= lo && cx <= hi && cz >= lo && cz <= hi;
    }

    private static String status(Path dir, FileChannel ch, long offset, int cx, int cz) throws IOException {
        ByteBuffer head = ByteBuffer.allocate(5);
        if (!readFully(ch, head, offset)) {
            return null;
        }
        int length = head.getInt(0); // вместе с байтом сжатия
        int type = head.get(4) & 0xFF;
        InputStream raw;
        if ((type & 0x80) != 0) { // большой чанк лежит отдельным файлом рядом
            raw = Files.newInputStream(dir.resolve("c." + cx + "." + cz + ".mcc"));
            type &= 0x7F;
        } else {
            if (length <= 1) {
                return null;
            }
            raw = new ChannelInput(ch, offset + 5, length - 1);
        }
        RegionFileVersion version = RegionFileVersion.fromId(type);
        if (version == null) {
            raw.close();
            return null;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(version.wrap(raw), 512))) {
            return rootStatus(in);
        }
    }

    /** Строка Status из корня NBT чанка; остальное пропускается, пока Status не встретится. */
    private static String rootStatus(DataInputStream in) throws IOException {
        if (in.readByte() != 10) {
            return null;
        }
        in.skipNBytes(in.readUnsignedShort());
        while (true) {
            byte type = in.readByte();
            if (type == 0) {
                return null;
            }
            String name = in.readUTF();
            if (type == 8 && "Status".equals(name)) {
                return in.readUTF();
            }
            skip(in, type);
        }
    }

    private static void skip(DataInputStream in, byte type) throws IOException {
        switch (type) {
            case 0 -> { }
            case 1 -> in.skipNBytes(1);
            case 2 -> in.skipNBytes(2);
            case 3, 5 -> in.skipNBytes(4);
            case 4, 6 -> in.skipNBytes(8);
            case 7 -> in.skipNBytes(in.readInt());
            case 8 -> in.skipNBytes(in.readUnsignedShort());
            case 9 -> {
                byte element = in.readByte();
                int n = in.readInt();
                for (int i = 0; i < n; i++) {
                    skip(in, element);
                }
            }
            case 10 -> {
                byte t;
                while ((t = in.readByte()) != 0) {
                    in.skipNBytes(in.readUnsignedShort());
                    skip(in, t);
                }
            }
            case 11 -> in.skipNBytes(4L * in.readInt());
            case 12 -> in.skipNBytes(8L * in.readInt());
            default -> throw new IOException("NBT: неизвестный тег " + type);
        }
    }

    private static boolean readFully(FileChannel ch, ByteBuffer buf, long pos) throws IOException {
        while (buf.hasRemaining()) {
            int n = ch.read(buf, pos + buf.position());
            if (n < 0) {
                return false;
            }
        }
        return true;
    }

    /** Кусок файла как поток: читается по мере распаковки, позиционно, потому что канал общий. */
    private static final class ChannelInput extends InputStream {
        private final FileChannel ch;
        private long pos;
        private long left;

        ChannelInput(FileChannel ch, long pos, long length) {
            this.ch = ch;
            this.pos = pos;
            this.left = length;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) {
                return -1;
            }
            int n = ch.read(ByteBuffer.wrap(b, off, (int) Math.min(len, left)), pos);
            if (n < 0) {
                left = 0;
                return -1;
            }
            pos += n;
            left -= n;
            return n;
        }
    }
}

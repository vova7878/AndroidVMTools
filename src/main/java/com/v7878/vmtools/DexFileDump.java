package com.v7878.vmtools;

import static com.v7878.dex.DexConstants.NO_INDEX;
import static com.v7878.dex.DexOffsets.CHECKSUM_DATA_START_OFFSET;
import static com.v7878.dex.DexOffsets.CHECKSUM_OFFSET;
import static com.v7878.dex.DexOffsets.DATA_SIZE_OFFSET;
import static com.v7878.dex.DexOffsets.DATA_START_OFFSET;
import static com.v7878.dex.DexOffsets.ENDIAN_TAG_OFFSET;
import static com.v7878.dex.DexOffsets.FILE_SIZE_OFFSET;
import static com.v7878.dex.DexOffsets.HEADER_OFF_OFFSET;
import static com.v7878.dex.DexOffsets.HEADER_SIZE_OFFSET;
import static com.v7878.dex.DexOffsets.SIGNATURE_DATA_START_OFFSET;
import static com.v7878.dex.DexOffsets.SIGNATURE_OFFSET;
import static com.v7878.dex.DexOffsets.SIGNATURE_SIZE;
import static com.v7878.foreign.MemoryLayout.PathElement.groupElement;
import static com.v7878.foreign.ValueLayout.JAVA_BYTE;
import static com.v7878.unsafe.AndroidUnsafe.ARRAY_BYTE_BASE_OFFSET;
import static com.v7878.unsafe.AndroidUnsafe.getBooleanN;
import static com.v7878.unsafe.AndroidUnsafe.getIntN;
import static com.v7878.unsafe.AndroidUnsafe.getWordN;
import static com.v7878.unsafe.AndroidUnsafe.putIntN;
import static com.v7878.unsafe.ArtVersion.A14;
import static com.v7878.unsafe.ArtVersion.A9;
import static com.v7878.unsafe.ArtVersion.ART_INDEX;
import static com.v7878.unsafe.DexFileUtils.DEXFILE_LAYOUT;
import static com.v7878.unsafe.DexFileUtils.getDexFileStruct;

import android.util.Pair;

import com.v7878.dex.DexConstants;
import com.v7878.dex.DexIO;
import com.v7878.dex.DexIO.DexReaderCache;
import com.v7878.dex.DexOffsets;
import com.v7878.dex.ReadOptions;
import com.v7878.dex.immutable.ClassDef;
import com.v7878.dex.immutable.Dex;
import com.v7878.foreign.MemorySegment;
import com.v7878.unsafe.DexFileUtils;
import com.v7878.unsafe.ExtraMemoryAccess;
import com.v7878.unsafe.VM;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.Adler32;

public final class DexFileDump {
    private DexFileDump() {
    }

    private static final int VERSION_OFFSET = 4;

    public static long getDexFileHeader(long dexfile_struct) {
        class Holder {
            static final long header_offset = DEXFILE_LAYOUT.byteOffset(groupElement("header_"));
        }
        return getWordN(dexfile_struct + Holder.header_offset);
    }

    /**
     * Standard dex and Dex container: same as (begin, size).
     * Compact: only main section of this dexfile.
     */
    public static MemorySegment getDexFile(long dexfile_struct) {
        long header = getDexFileHeader(dexfile_struct);
        long size = getIntN(header + FILE_SIZE_OFFSET);
        return MemorySegment.ofAddress(header).reinterpret(size);
    }

    /**
     * Standard dex: same as (begin, size).
     * Dex container: all dex files (starting from the first header).
     * Compact: shared data which is located after all non-shared data.
     */
    public static MemorySegment getDexFileData(long dexfile_struct) {
        if (ART_INDEX < A9) {
            return getDexFile(dexfile_struct);
        }
        class Holder {
            static final long data_begin_offset;
            static final long data_size_offset;

            static {
                if (ART_INDEX >= A14) {
                    data_begin_offset = DEXFILE_LAYOUT.byteOffset(
                            groupElement("data_"), groupElement("array_"));
                    data_size_offset = DEXFILE_LAYOUT.byteOffset(
                            groupElement("data_"), groupElement("size_"));
                } else {
                    data_begin_offset = DEXFILE_LAYOUT.byteOffset(groupElement("data_begin_"));
                    data_size_offset = DEXFILE_LAYOUT.byteOffset(groupElement("data_size_"));
                }
            }
        }
        long begin = getWordN(dexfile_struct + Holder.data_begin_offset);
        long size = getWordN(dexfile_struct + Holder.data_size_offset);
        return MemorySegment.ofAddress(begin).reinterpret(size);
    }

    public static boolean isCompactDex(long dexfile_struct) {
        if (ART_INDEX < A9) {
            return false;
        } else {
            class Holder {
                static final long is_compact_dex_offset = DEXFILE_LAYOUT
                        .byteOffset(groupElement("is_compact_dex_"));
            }
            return getBooleanN(dexfile_struct + Holder.is_compact_dex_offset);
        }
    }

    public static boolean isProtectedDex(long dexfile_struct) {
        long header = getDexFileHeader(dexfile_struct);
        return getIntN(header) == 0 || getIntN(header + FILE_SIZE_OFFSET) == 0;
    }

    @SuppressWarnings("PointlessBitwiseExpression")
    private static final int DEX_MAGIC = ('d' << 0) | ('e' << 8) | ('x' << 16) | ('\n' << 24);

    private static boolean isStandartDexVersion(int version) {
        return switch (version) {
            //noinspection PointlessBitwiseExpression
            case ('0' << 0) | ('4' << 8) | ('0' << 16) | ('\0' << 24),
                 ('0' << 0) | ('3' << 8) | ('9' << 16) | ('\0' << 24),
                 ('0' << 0) | ('3' << 8) | ('8' << 16) | ('\0' << 24),
                 ('0' << 0) | ('3' << 8) | ('7' << 16) | ('\0' << 24),
                 ('0' << 0) | ('3' << 8) | ('5' << 16) | ('\0' << 24) -> true;
            default -> false;
        };
    }

    private static boolean isDexContainerVersion(int version) {
        //noinspection PointlessBitwiseExpression
        return version == ('0' << 0 | '4' << 8 | '1' << 16 | '\0' << 24);
    }

    public static void fixProtectedDexHeader(
            long dexfile_struct, boolean skip_checksum_and_signature) {
        if (isCompactDex(dexfile_struct)) {
            throw new IllegalStateException("Compact dex is not supported");
        }
        long header = getDexFileHeader(dexfile_struct);
        int version = getIntN(header + VERSION_OFFSET);
        if (!isStandartDexVersion(version)) {
            throw new IllegalStateException(
                    "Unsupported dex version: " + Integer.toHexString(version));
        }
        int file_size = getIntN(header + DATA_START_OFFSET)
                + getIntN(header + DATA_SIZE_OFFSET);

        putIntN(header, DEX_MAGIC);
        putIntN(header + FILE_SIZE_OFFSET, file_size);
        putIntN(header + HEADER_SIZE_OFFSET, DexOffsets.BASE_HEADER_SIZE);
        putIntN(header + ENDIAN_TAG_OFFSET, DexConstants.ENDIAN_CONSTANT);

        if (skip_checksum_and_signature) {
            return;
        }

        MemorySegment dex_segment = MemorySegment.ofAddress(header).reinterpret(file_size);
        ByteBuffer dex_buffer = dex_segment.asByteBuffer();
        dex_buffer.order(ByteOrder.nativeOrder());

        byte[] signature;
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("unable to find SHA-1 MessageDigest", e);
        }
        dex_buffer.position(SIGNATURE_DATA_START_OFFSET);
        md.update(dex_buffer);
        signature = md.digest();
        if (signature.length != SIGNATURE_SIZE) {
            throw new RuntimeException("unexpected digest: " + signature.length + " bytes");
        }
        dex_buffer.position(SIGNATURE_OFFSET);
        dex_buffer.put(signature);

        Adler32 adler = new Adler32();
        dex_buffer.position(CHECKSUM_DATA_START_OFFSET);
        adler.update(dex_buffer);
        dex_buffer.putInt(CHECKSUM_OFFSET, (int) adler.getValue());
    }

    public static void fixProtectedDexHeader(long dexfile_struct) {
        fixProtectedDexHeader(dexfile_struct, true);
    }

    private static byte[] copyReadable(long address, long size) {
        byte[] out = new byte[Math.toIntExact(size)];

        var end = address + size;

        for (var entry : MMap.maps("self")) {
            if (!entry.perms().contains("r")) {
                continue;
            }
            if (entry.end() <= address || entry.start() >= end) {
                continue;
            }

            long copy_begin = Math.max(address, entry.start());
            long copy_end = Math.min(entry.end(), end);

            long copy_offset = copy_begin - address;
            long copy_size = copy_end - copy_begin;

            ExtraMemoryAccess.copyMemory(null, copy_begin,
                    out, ARRAY_BYTE_BASE_OFFSET + copy_offset,
                    copy_size);
        }

        return out;
    }

    public static byte[] getDexFileContent(long dexfile_struct) {
        if (isProtectedDex(dexfile_struct)) {
            fixProtectedDexHeader(dexfile_struct);
        }
        if (isCompactDex(dexfile_struct)) {
            var main_section = getDexFile(dexfile_struct);
            int main_size = Math.toIntExact(main_section.byteSize());
            var data_section = getDexFileData(dexfile_struct);
            int data_size = Math.toIntExact(data_section.byteSize());
            int data_offset = getIntN(main_section.nativeAddress() + DATA_START_OFFSET);
            byte[] out = new byte[data_offset + data_size];
            MemorySegment.copy(main_section, JAVA_BYTE, 0, out, 0, main_size);
            MemorySegment.copy(data_section, JAVA_BYTE, 0, out, data_offset, data_size);
            return out;
        }
        var data = getDexFileData(dexfile_struct);
        return copyReadable(data.nativeAddress(), data.byteSize());
    }

    public static byte[] getDexFileContent(Class<?> clazz) {
        return getDexFileContent(getDexFileStruct(clazz));
    }

    public static int getHeaderOffset(long dexfile_struct) {
        if (isCompactDex(dexfile_struct)) return 0;
        long header = getDexFileHeader(dexfile_struct);
        int version = getIntN(header + VERSION_OFFSET);
        if (isStandartDexVersion(version)) return 0;
        if (isDexContainerVersion(version))
            return getIntN(header + HEADER_OFF_OFFSET);
        throw new IllegalStateException(
                "Unsupported dex version: " + Integer.toHexString(version));
    }

    private static Map<Long, Set<Integer>> getStructMap(Class<?>... classes) {
        Map<Long, Set<Integer>> data = new HashMap<>();
        for (var clazz : classes) {
            int index = VM.getDexClassDefIndex(clazz);
            if (index == (NO_INDEX & 0xffff)) {
                throw new IllegalArgumentException(
                        String.format("Class %s has no index", clazz));
            }
            long struct = DexFileUtils.getDexFileStruct(clazz);
            data.computeIfAbsent(struct, v -> new HashSet<>()).add(index);
        }
        return data;
    }

    public static Dex readDex(Class<?>... classes) {
        var data = getStructMap(classes);
        var defs = new ArrayList<ClassDef>(classes.length);
        for (var entry : data.entrySet()) {
            int header_offset = getHeaderOffset(entry.getKey());
            defs.addAll(DexIO.read(ReadOptions.defaultOptions(),
                    getDexFileContent(entry.getKey()), 0, header_offset,
                    entry.getValue().stream().mapToInt(v -> v).toArray()).getClasses());
        }
        return Dex.of(defs);
    }

    public static List<Pair<ClassDef, DexReaderCache>> readWithCache(Class<?>... classes) {
        var data = getStructMap(classes);
        var defs = new ArrayList<Pair<ClassDef, DexReaderCache>>(classes.length);
        for (var entry : data.entrySet()) {
            int header_offset = getHeaderOffset(entry.getKey());
            var cache = DexIO.readCache(ReadOptions.defaultOptions(),
                    getDexFileContent(entry.getKey()), 0, header_offset);
            entry.getValue().stream()
                    .map(idx -> new Pair<>(cache.getClassDef(idx), cache))
                    .forEach(defs::add);
        }
        defs.trimToSize();
        return defs;
    }
}

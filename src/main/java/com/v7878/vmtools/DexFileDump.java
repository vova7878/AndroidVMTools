package com.v7878.vmtools;

import static com.v7878.foreign.MemoryLayout.PathElement.groupElement;
import static com.v7878.foreign.ValueLayout.JAVA_BYTE;
import static com.v7878.unsafe.AndroidUnsafe.getBooleanN;
import static com.v7878.unsafe.AndroidUnsafe.getIntN;
import static com.v7878.unsafe.AndroidUnsafe.getWordN;
import static com.v7878.unsafe.AndroidUnsafe.putIntN;
import static com.v7878.unsafe.ArtVersion.ART_SDK_INT;
import static com.v7878.unsafe.DexFileUtils.DEXFILE_LAYOUT;

import com.v7878.dex.DexConstants;
import com.v7878.foreign.MemorySegment;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.Adler32;

public final class DexFileDump {
    private DexFileDump() {
    }

    private static final int VERSION_OFFSET = 4;
    private static final int CHECKSUM_OFFSET = 8;

    // this is the start of the checksumed data
    private static final int CHECKSUM_DATA_START_OFFSET = 12;

    private static final int SIGNATURE_OFFSET = 12;
    private static final int SIGNATURE_SIZE = 20;

    // this is the start of the sha-1 hashed data
    private static final int SIGNATURE_DATA_START_OFFSET = 32;

    private static final int FILE_SIZE_OFFSET = 32;

    private static final int HEADER_SIZE_OFFSET = 36;

    private static final int ENDIAN_TAG_OFFSET = 40;

    private static final int DATA_SIZE_OFFSET = 104;
    private static final int DATA_START_OFFSET = 108;

    //private static final int CONTAINER_SIZE_OFFSET = 112;
    private static final int CONTAINER_OFF_OFFSET = 116;

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
        if (ART_SDK_INT < 28) {
            return getDexFile(dexfile_struct);
        }
        class Holder {
            static final long data_begin_offset;
            static final long data_size_offset;

            static {
                if (ART_SDK_INT >= 34) {
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
        if (ART_SDK_INT < 28) {
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
        putIntN(header + HEADER_SIZE_OFFSET, DexConstants.HEADER_SIZE);
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
        return getDexFileData(dexfile_struct).toArray(JAVA_BYTE);
    }

    public static int getHeaderOffset(long dexfile_struct) {
        if (isCompactDex(dexfile_struct)) return 0;
        long header = getDexFileHeader(dexfile_struct);
        int version = getIntN(header + VERSION_OFFSET);
        if (isStandartDexVersion(version)) return 0;
        if (isDexContainerVersion(version))
            return getIntN(header + CONTAINER_OFF_OFFSET);
        throw new IllegalStateException(
                "Unsupported dex version: " + Integer.toHexString(version));
    }
}

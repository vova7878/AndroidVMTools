package com.v7878.vmtools;

import static com.v7878.foreign.MemoryLayout.PathElement.groupElement;
import static com.v7878.misc.Version.CORRECT_SDK_INT;
import static com.v7878.unsafe.AndroidUnsafe.copyMemory;
import static com.v7878.unsafe.AndroidUnsafe.getBooleanN;
import static com.v7878.unsafe.AndroidUnsafe.getIntN;
import static com.v7878.unsafe.AndroidUnsafe.getWordN;
import static com.v7878.unsafe.DexFileUtils.DEXFILE_LAYOUT;

import com.v7878.dex.DexConstants;
import com.v7878.foreign.MemorySegment;
import com.v7878.unsafe.VM;

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
        if (CORRECT_SDK_INT < 28) {
            return getDexFile(dexfile_struct);
        }
        class Holder {
            static final long data_begin_offset;
            static final long data_size_offset;

            static {
                if (CORRECT_SDK_INT >= 34) {
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

    private static boolean checkDexVersion(int version) {
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

    public static byte[] getProtectedDex(long dexfile_struct) {
        boolean is_compact;
        if (CORRECT_SDK_INT < 28) {
            is_compact = false;
        } else {
            class Holder {
                static final long is_compact_dex_offset = DEXFILE_LAYOUT
                        .byteOffset(groupElement("is_compact_dex_"));
            }
            is_compact = getBooleanN(dexfile_struct + Holder.is_compact_dex_offset);
        }
        if (is_compact) {
            throw new IllegalStateException("Compact dex is not supported");
        }

        long header = getDexFileHeader(dexfile_struct);
        int version = getIntN(header + VERSION_OFFSET);
        if (!checkDexVersion(version)) {
            throw new IllegalStateException(
                    "Unsupported dex version: " + Integer.toHexString(version));
        }
        int file_size = getIntN(header + DATA_START_OFFSET)
                + getIntN(header + DATA_SIZE_OFFSET);

        byte[] data = (byte[]) VM.newNonMovableArray(byte.class, file_size);
        copyMemory(header, VM.addressOfNonMovableArrayData(data), file_size);

        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.nativeOrder());

        buffer.put(new byte[]{'d', 'e', 'x', '\n'});
        buffer.putInt(FILE_SIZE_OFFSET, file_size);
        buffer.putInt(HEADER_SIZE_OFFSET, 0x70);
        buffer.putInt(ENDIAN_TAG_OFFSET, DexConstants.ENDIAN_CONSTANT);

        byte[] signature;
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("unable to find SHA-1 MessageDigest", e);
        }
        md.update(data, SIGNATURE_DATA_START_OFFSET,
                data.length - SIGNATURE_DATA_START_OFFSET);
        signature = md.digest();
        if (signature.length != SIGNATURE_SIZE) {
            throw new RuntimeException("unexpected digest: " + signature.length + " bytes");
        }
        buffer.position(SIGNATURE_OFFSET);
        buffer.put(signature);

        Adler32 adler = new Adler32();
        adler.update(data, CHECKSUM_DATA_START_OFFSET,
                data.length - CHECKSUM_DATA_START_OFFSET);
        buffer.putInt(CHECKSUM_OFFSET, (int) adler.getValue());

        return data;
    }
}

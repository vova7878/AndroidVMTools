package com.v7878.vmtools;

import static com.v7878.foreign.MemoryLayout.PathElement.groupElement;
import static com.v7878.misc.Version.CORRECT_SDK_INT;
import static com.v7878.unsafe.AndroidUnsafe.getIntN;
import static com.v7878.unsafe.AndroidUnsafe.getWordN;
import static com.v7878.unsafe.DexFileUtils.DEXFILE_LAYOUT;

import com.v7878.foreign.MemorySegment;

public final class DexFileDump {
    private DexFileDump() {
    }

    public static MemorySegment getDexFile(long dexfile_struct) {
        class Holder {
            static final long header_offset = DEXFILE_LAYOUT.byteOffset(groupElement("header_"));
            static final long file_size_offset = 8 /* magic */ + 4 /* checksum */ + 20 /* signature */;
        }
        long header = getWordN(dexfile_struct + Holder.header_offset);
        long size = getIntN(header + Holder.file_size_offset);
        return MemorySegment.ofAddress(header).reinterpret(size);
    }

    // Standard dex: same as (begin_, size_).
    // Dex container: all dex files (starting from the first header).
    // Compact: shared data which is located after all non-shared data.
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
}

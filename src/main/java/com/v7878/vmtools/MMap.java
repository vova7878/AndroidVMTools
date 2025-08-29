package com.v7878.vmtools;

import static com.v7878.unsafe.Utils.shouldNotHappen;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class MMap {
    public record MMapEntry(long start, long end, String perms,
                            long offset, int dev_major, int dev_minor,
                            long inode, String path) {
    }

    private static final String PATTERN = "^"
            + "(?<start>[0-9A-Fa-f]+)-(?<end>[0-9A-Fa-f]+)\\s+"
            + "(?<perms>[rwxsp\\-]{4})\\s+"
            + "(?<offset>[0-9A-Fa-f]+)\\s+"
            + "(?<devMajor>[0-9A-Fa-f]+):(?<devMinor>[0-9A-Fa-f]+)\\s+"
            + "(?<inode>[0-9]+)\\s+"
            + "(?<path>.+)?"
            + "$";
    private static final Pattern ENTRY = Pattern.compile(PATTERN);

    public static Stream<MMapEntry> maps(String pid) {
        File maps = new File(String.format("/proc/%s/maps", pid));

        try {
            //noinspection resource
            return Files.lines(maps.toPath()).map(str -> {
                var match = ENTRY.matcher(str);
                assert match.matches();
                return new MMapEntry(
                        Long.parseUnsignedLong(Objects.requireNonNull(match.group("start")), 16),
                        Long.parseUnsignedLong(Objects.requireNonNull(match.group("end")), 16),
                        Objects.requireNonNull(match.group("perms")),
                        Long.parseUnsignedLong(Objects.requireNonNull(match.group("offset")), 16),
                        Integer.parseUnsignedInt(Objects.requireNonNull(match.group("devMajor")), 16),
                        Integer.parseUnsignedInt(Objects.requireNonNull(match.group("devMinor")), 16),
                        Long.parseUnsignedLong(Objects.requireNonNull(match.group("inode"))),
                        match.group("path") // nullable
                );
            });
        } catch (IOException e) {
            throw shouldNotHappen(e);
        }
    }
}

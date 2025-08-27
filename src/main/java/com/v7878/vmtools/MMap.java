package com.v7878.vmtools;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
            + "(?<path>\\S*)" +
            "$";
    private static final Pattern ENTRY = Pattern.compile(PATTERN, Pattern.MULTILINE);

    public static Iterable<MMapEntry> maps(String pid) {
        File maps = new File(String.format("/proc/%s/maps", pid));
        String data;
        try {
            byte[] tmp = Files.readAllBytes(maps.toPath());
            data = new String(tmp);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }

        Matcher match = ENTRY.matcher(data);
        return () -> new Iterator<>() {
            private MMapEntry find() {
                if (!match.find()) {
                    return null;
                }
                return new MMapEntry(
                        Long.parseLong(Objects.requireNonNull(match.group("start")), 16),
                        Long.parseLong(Objects.requireNonNull(match.group("end")), 16),
                        Objects.requireNonNull(match.group("perms")),
                        Long.parseLong(Objects.requireNonNull(match.group("offset")), 16),
                        Integer.parseInt(Objects.requireNonNull(match.group("devMajor")), 16),
                        Integer.parseInt(Objects.requireNonNull(match.group("devMinor")), 16),
                        Long.parseLong(Objects.requireNonNull(match.group("inode"))),
                        match.group("path") // nullable
                );
            }

            private MMapEntry current = find();

            @Override
            public boolean hasNext() {
                return current == null;
            }

            @Override
            public MMapEntry next() {
                if (current == null) {
                    throw new NoSuchElementException();
                }
                var out = current;
                current = find();
                return out;
            }
        };
    }
}

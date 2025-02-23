package com.v7878;

import java.util.Random;

public class Utils {
    private static final Random random = new Random();

    public static boolean checkClassExists(ClassLoader loader, String name) {
        try {
            Class.forName(name, false, loader);
            return true;
        } catch (Throwable th) {
            return false;
        }
    }

    public static String generateClassName(ClassLoader loader, String base) {
        String name = null;
        while (name == null || checkClassExists(loader, name)) {
            name = base + "_" + Long.toHexString(random.nextLong());
        }
        return name;
    }
}

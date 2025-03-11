package com.v7878.vmtools;

import static com.v7878.unsafe.AndroidUnsafe.PAGE_SIZE;
import static com.v7878.unsafe.Utils.shouldNotHappen;
import static com.v7878.unsafe.misc.Math.roundDownUL;
import static com.v7878.unsafe.misc.Math.roundUpUL;

import android.system.ErrnoException;
import android.system.OsConstants;

import com.v7878.unsafe.io.IOUtils;

import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

final class _Utils {
    private static final Random random = new Random();

    public static boolean checkClassExists(ClassLoader loader, String name) {
        try {
            Class.forName(name, false, loader);
            return true;
        } catch (ClassNotFoundException th) {
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

    public static MethodType rawMethodTypeOf(Executable ex) {
        Class<?> ret;
        List<Class<?>> args = new ArrayList<>();
        if (ex instanceof Method m) {
            ret = m.getReturnType();
            if (!Modifier.isStatic(m.getModifiers())) {
                args.add(m.getDeclaringClass());
            }
            args.addAll(List.of(m.getParameterTypes()));
        } else {
            assert ex instanceof Constructor<?>;
            ret = void.class;
            args.add(ex.getDeclaringClass());
            args.addAll(List.of(ex.getParameterTypes()));
        }
        return MethodType.methodType(ret, args);
    }

    public static final int PROT_RX = OsConstants.PROT_READ | OsConstants.PROT_EXEC;
    public static final int PROT_RWX = PROT_RX | OsConstants.PROT_WRITE;

    public static void aligned_mprotect(long address, long length, int prot) {
        long end = roundUpUL(address + length, PAGE_SIZE);
        long begin = roundDownUL(address, PAGE_SIZE);
        try {
            IOUtils.mprotect(begin, end - begin, prot);
        } catch (ErrnoException e) {
            throw shouldNotHappen(e);
        }
    }
}

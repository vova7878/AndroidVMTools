package com.v7878.vmtools;

import static com.v7878.unsafe.ArtMethodUtils.getExecutableEntryPoint;
import static com.v7878.unsafe.Reflection.getDeclaredMethod;

import androidx.annotation.Keep;

public class EntryPoints {
    @SuppressWarnings("unused")
    private static abstract class Test {
        @Keep
        abstract void interpreter();

        @Keep
        native void jni();
    }

    private static final long generic_jni_trampoline = getExecutableEntryPoint(
            getDeclaredMethod(Test.class, "jni"));
    private static final long to_interpreter_bridge = getExecutableEntryPoint(
            getDeclaredMethod(Test.class, "interpreter"));

    public static long getGenericJniTrampoline() {
        return generic_jni_trampoline;
    }

    public static long getToInterpreterBridge() {
        return to_interpreter_bridge;
    }
}
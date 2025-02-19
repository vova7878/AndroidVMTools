package com.v7878.vmtools;

import static com.v7878.dex.DexConstants.ACC_ABSTRACT;
import static com.v7878.dex.DexConstants.ACC_NATIVE;
import static com.v7878.dex.DexConstants.ACC_PRIVATE;
import static com.v7878.dex.DexConstants.ACC_PUBLIC;
import static com.v7878.unsafe.ArtMethodUtils.getExecutableEntryPoint;
import static com.v7878.unsafe.Reflection.getDeclaredMethod;

import com.v7878.dex.DexIO;
import com.v7878.dex.builder.ClassBuilder;
import com.v7878.dex.immutable.ClassDef;
import com.v7878.dex.immutable.Dex;
import com.v7878.dex.immutable.MethodId;
import com.v7878.dex.immutable.ProtoId;
import com.v7878.dex.immutable.TypeId;
import com.v7878.unsafe.DexFileUtils;

import java.lang.invoke.MethodType;

public class EntryPoints {
    private static final long generic_jni_trampoline;
    private static final long to_interpreter_bridge;

    // Art can cache trampolines for common native call types, so we have to do something uncommon
    private static final MethodType JNI_TYPE = MethodType.methodType(Object.class,
            Object.class, int.class, long.class, float.class, double.class,
            Object.class, int.class, long.class, float.class, double.class,
            Object.class, int.class, long.class, float.class, double.class,
            Object.class, int.class, long.class, float.class, double.class,
            Object.class, int.class, long.class, float.class, double.class,
            Object.class, int.class, long.class, float.class, double.class);

    static {
        TypeId obj_id = TypeId.OBJECT;

        String test_name = EntryPoints.class.getName() + "$Test";
        TypeId test_id = TypeId.ofName(test_name);

        var jni_method_id = MethodId.of(test_id, "jni", ProtoId.of(JNI_TYPE));
        var interpreter_method_id = MethodId.of(test_id, "interpreter", ProtoId.of(TypeId.V));

        ClassDef test_def = ClassBuilder.build(test_id, cb -> cb
                .withSuperClass(obj_id)
                .withFlags(ACC_ABSTRACT)
                .withMethod(mb -> mb
                        .of(jni_method_id)
                        .withFlags(ACC_PRIVATE | ACC_NATIVE)
                )
                .withMethod(mb -> mb
                        .of(interpreter_method_id)
                        .withFlags(ACC_PUBLIC | ACC_ABSTRACT)
                )
        );

        var dexfile = DexFileUtils.openDexFile(DexIO.write(Dex.of(test_def)));
        var clazz = DexFileUtils.loadClass(dexfile, test_name, EntryPoints.class.getClassLoader());

        generic_jni_trampoline = getExecutableEntryPoint(
                getDeclaredMethod(clazz, "jni", JNI_TYPE.parameterArray()));
        to_interpreter_bridge = getExecutableEntryPoint(
                getDeclaredMethod(clazz, "interpreter"));
    }

    public static long getGenericJniTrampoline() {
        return generic_jni_trampoline;
    }

    public static long getToInterpreterBridge() {
        return to_interpreter_bridge;
    }
}

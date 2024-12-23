package com.v7878.vmtools;

import static com.v7878.dex.DexConstants.ACC_ABSTRACT;
import static com.v7878.dex.DexConstants.ACC_NATIVE;
import static com.v7878.dex.DexConstants.ACC_PRIVATE;
import static com.v7878.dex.DexConstants.ACC_PUBLIC;
import static com.v7878.unsafe.ArtMethodUtils.getExecutableEntryPoint;
import static com.v7878.unsafe.Reflection.getDeclaredMethod;

import com.v7878.dex.ClassDef;
import com.v7878.dex.Dex;
import com.v7878.dex.EncodedMethod;
import com.v7878.dex.MethodId;
import com.v7878.dex.ProtoId;
import com.v7878.dex.TypeId;
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
        TypeId test_id = TypeId.of(test_name);
        ClassDef test_def = new ClassDef(test_id);
        test_def.setSuperClass(obj_id);

        MethodId jni_method_id = new MethodId(test_id, ProtoId.of(JNI_TYPE), "jni");
        test_def.getClassData().getDirectMethods().add(
                new EncodedMethod(jni_method_id, ACC_PRIVATE | ACC_NATIVE));

        MethodId interpreter_method_id = new MethodId(test_id, new ProtoId(TypeId.V), "interpreter");
        test_def.getClassData().getVirtualMethods().add(
                new EncodedMethod(interpreter_method_id, ACC_PUBLIC | ACC_ABSTRACT));

        var dexfile = DexFileUtils.openDexFile(new Dex(test_def).compile());
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

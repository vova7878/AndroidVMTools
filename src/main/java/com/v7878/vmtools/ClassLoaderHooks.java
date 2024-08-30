package com.v7878.vmtools;

import static com.v7878.dex.bytecode.CodeBuilder.InvokeKind.INTERFACE;
import static com.v7878.dex.bytecode.CodeBuilder.Op.GET_OBJECT;
import static com.v7878.unsafe.ArtMethodUtils.makeMethodInheritable;
import static com.v7878.unsafe.ClassUtils.makeClassInheritable;
import static com.v7878.unsafe.DexFileUtils.loadClass;
import static com.v7878.unsafe.DexFileUtils.openDexFile;
import static com.v7878.unsafe.DexFileUtils.setTrusted;
import static com.v7878.unsafe.Reflection.getDeclaredField;
import static com.v7878.unsafe.Reflection.getMethods;
import static com.v7878.unsafe.Reflection.unreflectDirect;
import static com.v7878.unsafe.Utils.assert_;
import static com.v7878.unsafe.Utils.nothrows_run;
import static com.v7878.unsafe.Utils.searchMethod;
import static com.v7878.unsafe.VM.objectSizeField;
import static com.v7878.unsafe.VM.setObjectClass;

import com.v7878.dex.ClassDef;
import com.v7878.dex.Dex;
import com.v7878.dex.EncodedField;
import com.v7878.dex.EncodedMethod;
import com.v7878.dex.FieldId;
import com.v7878.dex.MethodId;
import com.v7878.dex.ProtoId;
import com.v7878.dex.TypeId;
import com.v7878.unsafe.AndroidUnsafe;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;
import java.util.function.BiFunction;

import dalvik.system.DexFile;

public class ClassLoaderHooks {
    private static final Object LOCK = new Object();

    @FunctionalInterface
    public interface FindClassBackup {
        Class<?> findClass(String name) throws ClassNotFoundException;
    }

    @FunctionalInterface
    public interface FindClassI {
        Class<?> findClass(ClassLoader thiz, String name,
                           FindClassBackup original) throws ClassNotFoundException;
    }

    private record FindClassCallback(FindClassI impl, FindClassBackup original)
            implements BiFunction<ClassLoader, String, Class<?>> {
        @Override
        public Class<?> apply(ClassLoader thiz, String name) {
            try {
                return impl.findClass(thiz, name, original);
            } catch (ClassNotFoundException e) {
                return AndroidUnsafe.throwException(e);
            }
        }
    }

    //TODO: add a way to call original findClass method
    public static void hookFindClass(ClassLoader loader, FindClassI impl) {
        Objects.requireNonNull(loader);
        Objects.requireNonNull(impl);
        synchronized (LOCK) {
            Class<?> lc = loader.getClass();
            makeClassInheritable(lc);
            // note: maybe super method
            Method fc = searchMethod(getMethods(lc), "findClass", String.class);
            makeMethodInheritable(fc);

            MethodHandle original = unreflectDirect(fc);

            String hook_name = lc.getName() + "$$$SyntheticHook";
            TypeId hook_id = TypeId.of(hook_name);
            ClassDef hook_def = new ClassDef(hook_id);
            hook_def.setSuperClass(TypeId.of(lc));
            FieldId impl_f_id = new FieldId(hook_id, TypeId.of(BiFunction.class), "impl");
            hook_def.getClassData().getStaticFields().add(new EncodedField(
                    impl_f_id, Modifier.STATIC, null
            ));
            hook_def.getClassData().getVirtualMethods().add(new EncodedMethod(
                    new MethodId(hook_id, new ProtoId(TypeId.of(Class.class),
                            TypeId.of(String.class)), "findClass"),
                    Modifier.PUBLIC).withCode(1, b -> b
                    .sop(GET_OBJECT, b.l(0), impl_f_id)
                    .invoke(INTERFACE, new MethodId(TypeId.of(BiFunction.class), new ProtoId(
                                    TypeId.of(Object.class), TypeId.of(Object.class),
                                    TypeId.of(Object.class)), "apply"),
                            b.l(0), b.this_(), b.p(0))
                    .move_result_object(b.l(0))
                    .check_cast(b.l(0), TypeId.of(Class.class))
                    .return_object(b.l(0))
            ));

            DexFile dex = openDexFile(new Dex(hook_def).compile());
            setTrusted(dex);
            Class<?> hook = loadClass(dex, hook_name, lc.getClassLoader());
            Field impl_f = getDeclaredField(hook, "impl");
            nothrows_run(() -> impl_f.set(null, new FindClassCallback(impl, name -> {
                try {
                    return (Class<?>) original.invoke(loader, name);
                } catch (Throwable th) {
                    return AndroidUnsafe.throwException(th);
                }
            })));

            assert_(objectSizeField(lc) == objectSizeField(hook), AssertionError::new);

            setObjectClass(loader, hook);
        }
    }
}

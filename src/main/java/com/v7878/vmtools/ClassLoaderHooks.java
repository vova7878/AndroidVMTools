package com.v7878.vmtools;

import static com.v7878.dex.DexConstants.ACC_FINAL;
import static com.v7878.dex.DexConstants.ACC_PRIVATE;
import static com.v7878.dex.DexConstants.ACC_PUBLIC;
import static com.v7878.dex.DexConstants.ACC_STATIC;
import static com.v7878.dex.builder.CodeBuilder.InvokeKind.INTERFACE;
import static com.v7878.dex.builder.CodeBuilder.InvokeKind.STATIC;
import static com.v7878.dex.builder.CodeBuilder.InvokeKind.SUPER;
import static com.v7878.dex.builder.CodeBuilder.Op.GET_OBJECT;
import static com.v7878.dex.builder.CodeBuilder.Test.EQ;
import static com.v7878.unsafe.ArtMethodUtils.makeMethodInheritable;
import static com.v7878.unsafe.ClassUtils.makeClassInheritable;
import static com.v7878.unsafe.DexFileUtils.loadClass;
import static com.v7878.unsafe.DexFileUtils.openDexFile;
import static com.v7878.unsafe.DexFileUtils.setTrusted;
import static com.v7878.unsafe.Reflection.getDeclaredField;
import static com.v7878.unsafe.Reflection.getDeclaredVirtualMethods;
import static com.v7878.unsafe.Utils.check;
import static com.v7878.unsafe.Utils.nothrows_run;
import static com.v7878.unsafe.Utils.searchMethod;
import static com.v7878.unsafe.VM.objectSizeField;
import static com.v7878.unsafe.VM.setObjectClass;

import com.v7878.dex.DexIO;
import com.v7878.dex.builder.ClassBuilder;
import com.v7878.dex.immutable.ClassDef;
import com.v7878.dex.immutable.Dex;
import com.v7878.dex.immutable.FieldId;
import com.v7878.dex.immutable.MethodId;
import com.v7878.dex.immutable.ProtoId;
import com.v7878.dex.immutable.TypeId;
import com.v7878.unsafe.AndroidUnsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.function.BiFunction;

import dalvik.system.DexFile;

public class ClassLoaderHooks {
    private static final Object LOCK = new Object();

    @FunctionalInterface
    public interface FindClassBackup {
        Class<?> findClass(ClassLoader loader, String name) throws ClassNotFoundException;
    }

    @FunctionalInterface
    public interface FindClassI {
        Class<?> findClass(ClassLoader loader, String name,
                           FindClassBackup original) throws ClassNotFoundException;
    }

    private static final class FindClassCallback
            implements BiFunction<ClassLoader, String, Class<?>> {
        private final FindClassI impl;
        private final FindClassBackup original;

        private FindClassCallback(
                FindClassI impl, BiFunction<ClassLoader, String, Class<?>> backup) {
            this.impl = impl;
            this.original = backup::apply;
        }

        @Override
        public Class<?> apply(ClassLoader thiz, String name) {
            try {
                return impl.findClass(thiz, name, original);
            } catch (ClassNotFoundException e) {
                return AndroidUnsafe.throwException(e);
            }
        }
    }

    @SuppressWarnings("SameParameterValue")
    private static Method findMethod(Class<?> clazz, String name, Class<?>... args) {
        while (clazz != null) {
            var method = searchMethod(getDeclaredVirtualMethods(clazz), name, false, args);
            if (method != null) return method;
            clazz = clazz.getSuperclass();
        }
        throw new AssertionError();
    }

    @SuppressWarnings("unchecked")
    public static void hookFindClass(ClassLoader loader, FindClassI impl) {
        Objects.requireNonNull(loader);
        Objects.requireNonNull(impl);
        synchronized (LOCK) {
            Class<?> lc = loader.getClass();
            makeClassInheritable(lc);
            // Note: maybe super method
            Method fc = findMethod(lc, "findClass", String.class);
            makeMethodInheritable(fc);

            TypeId bf = TypeId.of(BiFunction.class);
            ProtoId apply_proto = ProtoId.of(TypeId.OBJECT, TypeId.OBJECT, TypeId.OBJECT);

            // TODO: Dynamic name selection
            String hook_name = lc.getName() + "$$$SyntheticHook";
            TypeId hook_id = TypeId.ofName(hook_name);

            var impl_f_id = FieldId.of(hook_id, "impl", bf);
            var hook_find_id = MethodId.of(hook_id, "findClass",
                    ProtoId.of(TypeId.of(Class.class), TypeId.of(String.class)));
            var super_find_id = MethodId.of(hook_id, "superFindClass",
                    ProtoId.of(TypeId.of(Class.class), hook_id, TypeId.of(String.class)));

            ClassDef hook_def = ClassBuilder.build(hook_id, cb -> cb
                    .withSuperClass(TypeId.of(lc))
                    .withFlags(ACC_PUBLIC | ACC_FINAL)
                    .withField(fb -> fb
                            .of(impl_f_id)
                            .withFlags(ACC_PRIVATE | ACC_STATIC | ACC_FINAL)
                    )
                    .withMethod(mb -> mb
                            .of(hook_find_id)
                            .withFlags(ACC_PUBLIC)
                            .withCode(1, ib -> ib
                                    .sop(GET_OBJECT, ib.l(0), impl_f_id)
                                    .invoke(INTERFACE, MethodId.of(bf, "apply", apply_proto),
                                            ib.l(0), ib.this_(), ib.p(0))
                                    .move_result_object(ib.l(0))
                                    .check_cast(ib.l(0), TypeId.of(Class.class))
                                    .if_testz(EQ, ib.l(0), ":null")
                                    .return_object(ib.l(0))

                                    .label(":null")
                                    .invoke(SUPER, MethodId.of(fc), ib.this_(), ib.p(0))
                                    .move_result_object(ib.l(0))
                                    .return_object(ib.l(0))
                            )
                    )
                    .withMethod(mb -> mb
                            .of(super_find_id)
                            .withFlags(ACC_PUBLIC | ACC_STATIC)
                            .withCode(0, ib -> ib
                                    .invoke(SUPER, hook_find_id, ib.p(0), ib.p(1))
                                    .move_result_object(ib.v(0))
                                    .return_object(ib.v(0))
                            )
                    )
            );

            String backup_name = hook_name + "$$$Backup";
            TypeId backup_id = TypeId.ofName(backup_name);
            var backup_find_id = MethodId.of(backup_id, "apply", apply_proto);

            ClassDef backup_def = ClassBuilder.build(backup_id, cb -> cb
                    .withSuperClass(TypeId.OBJECT)
                    .withInterfaces(bf)
                    .withFlags(ACC_PUBLIC | ACC_FINAL)
                    .withMethod(mb -> mb
                            .of(backup_find_id)
                            .withFlags(ACC_PUBLIC)
                            .withCode(0, ib -> ib
                                    .check_cast(ib.p(0), hook_id)
                                    .check_cast(ib.p(1), TypeId.of(String.class))
                                    .invoke(STATIC, super_find_id, ib.p(0), ib.p(1))
                                    .move_result_object(ib.v(0))
                                    .return_object(ib.v(0))
                            )
                    )
            );

            DexFile dex = openDexFile(DexIO.write(Dex.of(hook_def, backup_def)));
            setTrusted(dex);

            Class<?> hook = loadClass(dex, hook_name, lc.getClassLoader());
            Class<?> backup = loadClass(dex, backup_name, lc.getClassLoader());

            var backup_instance = (BiFunction<ClassLoader, String, Class<?>>)
                    AndroidUnsafe.allocateInstance(backup);
            Field impl_f = getDeclaredField(hook, "impl");
            nothrows_run(() -> impl_f.set(null, new FindClassCallback(impl, backup_instance)));

            check(objectSizeField(lc) == objectSizeField(hook), AssertionError::new);

            setObjectClass(loader, hook);
        }
    }
}

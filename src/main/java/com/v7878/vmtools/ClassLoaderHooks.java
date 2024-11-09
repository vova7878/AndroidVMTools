package com.v7878.vmtools;

import static com.v7878.dex.DexConstants.ACC_PUBLIC;
import static com.v7878.dex.DexConstants.ACC_STATIC;
import static com.v7878.dex.bytecode.CodeBuilder.InvokeKind.INTERFACE;
import static com.v7878.dex.bytecode.CodeBuilder.InvokeKind.STATIC;
import static com.v7878.dex.bytecode.CodeBuilder.InvokeKind.SUPER;
import static com.v7878.dex.bytecode.CodeBuilder.Op.GET_OBJECT;
import static com.v7878.dex.bytecode.CodeBuilder.Test.EQ;
import static com.v7878.unsafe.ArtMethodUtils.makeMethodInheritable;
import static com.v7878.unsafe.ClassUtils.makeClassInheritable;
import static com.v7878.unsafe.DexFileUtils.loadClass;
import static com.v7878.unsafe.DexFileUtils.openDexFile;
import static com.v7878.unsafe.DexFileUtils.setTrusted;
import static com.v7878.unsafe.Reflection.getDeclaredField;
import static com.v7878.unsafe.Reflection.getMethods;
import static com.v7878.unsafe.Utils.check;
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

    @SuppressWarnings("unchecked")
    public static void hookFindClass(ClassLoader loader, FindClassI impl) {
        Objects.requireNonNull(loader);
        Objects.requireNonNull(impl);
        synchronized (LOCK) {
            Class<?> lc = loader.getClass();
            makeClassInheritable(lc);
            // note: maybe super method
            Method fc = searchMethod(getMethods(lc), "findClass", String.class);
            makeMethodInheritable(fc);

            ProtoId apply_proto = new ProtoId(TypeId.OBJECT, TypeId.OBJECT, TypeId.OBJECT);

            String hook_name = lc.getName() + "$$$SyntheticHook";
            TypeId hook_id = TypeId.of(hook_name);
            ClassDef hook_def = new ClassDef(hook_id);
            hook_def.setSuperClass(TypeId.of(lc));
            FieldId impl_f_id = new FieldId(hook_id, TypeId.of(BiFunction.class), "impl");
            hook_def.getClassData().getStaticFields().add(new EncodedField(
                    impl_f_id, Modifier.STATIC, null
            ));
            var hook_find_id = new MethodId(hook_id, new ProtoId(
                    TypeId.of(Class.class), TypeId.of(String.class)), "findClass");
            hook_def.getClassData().getVirtualMethods().add(new EncodedMethod(
                    hook_find_id, ACC_PUBLIC).withCode(1, b -> b
                    .sop(GET_OBJECT, b.l(0), impl_f_id)
                    .invoke(INTERFACE, new MethodId(
                                    TypeId.of(BiFunction.class), apply_proto, "apply"),
                            b.l(0), b.this_(), b.p(0))
                    .move_result_object(b.l(0))
                    .check_cast(b.l(0), TypeId.of(Class.class))
                    .if_testz(EQ, b.l(0), ":null")
                    .return_object(b.l(0))
                    .label(":null")
                    .invoke(SUPER, MethodId.of(fc), b.this_(), b.p(0))
                    .move_result_object(b.l(0))
                    .return_object(b.l(0))
            ));
            var super_find_id = new MethodId(hook_id, new ProtoId(TypeId.of(Class.class),
                    hook_id, TypeId.of(String.class)), "superFindClass");
            hook_def.getClassData().getDirectMethods().add(new EncodedMethod(super_find_id,
                    ACC_PUBLIC | ACC_STATIC).withCode(0, b -> b
                    .invoke(SUPER, hook_find_id, b.p(0), b.p(1))
                    .move_result_object(b.v(0))
                    .return_object(b.v(0))
            ));

            String backup_name = hook_name + "$$$Backup";
            TypeId backup_id = TypeId.of(backup_name);
            ClassDef backup_def = new ClassDef(backup_id);
            backup_def.setSuperClass(TypeId.OBJECT);
            backup_def.getInterfaces().add(TypeId.of(BiFunction.class));
            var backup_find_id = new MethodId(backup_id, apply_proto, "apply");
            backup_def.getClassData().getVirtualMethods().add(new EncodedMethod(
                    backup_find_id, ACC_PUBLIC).withCode(0, b -> b
                    .check_cast(b.p(0), hook_id)
                    .check_cast(b.p(1), TypeId.of(String.class))
                    .invoke(STATIC, super_find_id, b.p(0), b.p(1))
                    .move_result_object(b.v(0))
                    .return_object(b.v(0))
            ));

            DexFile dex = openDexFile(new Dex(hook_def, backup_def).compile());
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

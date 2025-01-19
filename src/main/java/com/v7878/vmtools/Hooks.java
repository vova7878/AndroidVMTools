package com.v7878.vmtools;

import static com.v7878.dex.DexConstants.ACC_CONSTRUCTOR;
import static com.v7878.dex.DexConstants.ACC_STATIC;
import static com.v7878.dex.bytecode.CodeBuilder.Op.GET_OBJECT;
import static com.v7878.unsafe.ArtModifiers.kAccFastInterpreterToInterpreterInvoke;
import static com.v7878.unsafe.InstructionSet.CURRENT_INSTRUCTION_SET;
import static com.v7878.unsafe.Reflection.fieldOffset;
import static com.v7878.unsafe.Reflection.getArtMethod;
import static com.v7878.unsafe.Reflection.getDeclaredField;
import static com.v7878.unsafe.Reflection.getDeclaredMethod;
import static com.v7878.unsafe.Reflection.unreflect;
import static com.v7878.unsafe.Utils.shouldNotReachHere;

import com.v7878.dex.ClassDef;
import com.v7878.dex.Dex;
import com.v7878.dex.EncodedField;
import com.v7878.dex.EncodedMethod;
import com.v7878.dex.FieldId;
import com.v7878.dex.MethodId;
import com.v7878.dex.ProtoId;
import com.v7878.dex.TypeId;
import com.v7878.dex.bytecode.CodeBuilder;
import com.v7878.foreign.Arena;
import com.v7878.foreign.MemorySegment;
import com.v7878.unsafe.AndroidUnsafe;
import com.v7878.unsafe.ArtMethodUtils;
import com.v7878.unsafe.ClassUtils;
import com.v7878.unsafe.DexFileUtils;
import com.v7878.unsafe.NativeCodeBlob;
import com.v7878.unsafe.Utils;
import com.v7878.unsafe.Utils.WeakReferenceCache;
import com.v7878.unsafe.access.InvokeAccess;
import com.v7878.unsafe.invoke.EmulatedStackFrame;
import com.v7878.unsafe.invoke.MethodHandlesFixes;
import com.v7878.unsafe.invoke.Transformers;
import com.v7878.unsafe.invoke.Transformers.AbstractTransformer;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import sun.misc.Cleaner;

public class Hooks {
    static {
        ClassUtils.ensureClassInitialized(EntryPoints.class);
        DebugState.setRuntimeDebugState(DebugState.kNonJavaDebuggable);
    }

    private static void ensureDeclaringClassInitialized(Executable ex) {
        int flags = ArtMethodUtils.getExecutableFlags(ex);
        // For static constructor hook, the class CANNOT be initialized
        if ((flags & ACC_CONSTRUCTOR) == 0 || (flags & ACC_STATIC) == 0) {
            ClassUtils.ensureClassVisiblyInitialized(ex.getDeclaringClass());
        }
    }

    public static void deoptimize(Executable ex) {
        ensureDeclaringClassInitialized(ex);
        try (var ignored = new ScopedSuspendAll(false)) {
            ArtMethodUtils.makeExecutableNonCompilable(ex);
            long entry_point = Modifier.isNative(ex.getModifiers()) ?
                    EntryPoints.getGenericJniTrampoline() :
                    EntryPoints.getToInterpreterBridge();
            ArtMethodUtils.setExecutableEntryPoint(ex, entry_point);
        }
    }

    private static byte[] toArray(long value) {
        //noinspection PointlessBitwiseExpression
        return new byte[]{
                (byte) (value >> 0),
                (byte) (value >> 8),
                (byte) (value >> 16),
                (byte) (value >> 24),
                (byte) (value >> 32),
                (byte) (value >> 40),
                (byte) (value >> 48),
                (byte) (value >> 56)
        };
    }

    private static byte[] getTrampolineArray(long art_method, long entry_point) {
        byte[] m = toArray(art_method);
        byte[] e = toArray(entry_point);
        return switch (CURRENT_INSTRUCTION_SET) {
            case X86 -> new byte[]{
                    // b8 <m0 m1 m2 m3> ; mov eax, art_method
                    // 68 <e0 e1 e2 e3> ; push entry_point
                    // c3 ; ret
                    (byte) 0xb8, m[0], m[1], m[2], m[3],
                    0x68, e[0], e[1], e[2], e[3],
                    (byte) 0xc3
            };
            case X86_64 -> new byte[]{
                    // 48 bf <e0 e1 e2 e3 e4 e5 e6 e7> ; movabs rdi, entry_point
                    // 57 ; push rdi
                    // 48 bf <m0 m1 m2 m3 m4 m5 m6 m7> ; movabs rdi, art_method
                    // c3 ; ret
                    0x48, (byte) 0xbf, e[0], e[1], e[2], e[3], e[4], e[5], e[6], e[7],
                    0x57,
                    0x48, (byte) 0xbf, m[0], m[1], m[2], m[3], m[4], m[5], m[6], m[7],
                    (byte) 0xc3
            };
            case ARM -> new byte[]{
                    // 0C 00 9F E5 ; ldr r0, [pc, #12]
                    // 01 00 2D E9 ; stmdb sp!, {r0} ; push {r0}
                    // 00 00 9F E5 ; ldr r0, [pc, #0]
                    // 00 80 BD E8 ; ldm sp!, {pc} ; pop {pc}
                    // <m0 m1 m2 m3> ; art_method
                    // <e0 e1 e2 e3> ; entry_point
                    0x0c, 0x00, (byte) 0x9f, (byte) 0xe5,
                    0x01, 0x00, 0x2d, (byte) 0xe9,
                    0x00, 0x00, (byte) 0x9f, (byte) 0xe5,
                    0x00, (byte) 0x80, (byte) 0xbd, (byte) 0xe8,
                    m[0], m[1], m[2], m[3],
                    e[0], e[1], e[2], e[3]
            };
            case ARM64 -> new byte[]{
                    // 60 00 00 58 ; ldr x0, #12
                    // 90 00 00 58 ; ldr x16, #16
                    // 00 02 1f d6 ; br x16
                    // <m0 m1 m2 m3 m4 m5 m6 m7> ; art_method
                    // <e0 e1 e2 e3 e4 e5 e6 e7> ; entry_point
                    0x60, 0x00, 0x00, 0x58,
                    (byte) 0x90, 0x00, 0x00, 0x58,
                    0x00, 0x02, 0x1f, (byte) 0xd6,
                    m[0], m[1], m[2], m[3], m[4], m[5], m[6], m[7],
                    e[0], e[1], e[2], e[3], e[4], e[5], e[6], e[7]
            };
            //TODO: riscv64
            default -> throw new UnsupportedOperationException("Not supported yet!");
        };
    }

    /**
     * The declaring classes of target and hooker MUST be visible initialised
     */
    private static void hook(Executable target, Executable hooker, long hooker_entry_point) {
        //TODO: check signatures
        Objects.requireNonNull(target);
        Objects.requireNonNull(hooker);
        Arena scope = Arena.ofShared();
        Cleaner.create(target.getDeclaringClass(), scope::close);
        MemorySegment new_entry_point = NativeCodeBlob.makeCodeBlob(scope,
                getTrampolineArray(getArtMethod(hooker), hooker_entry_point))[0];
        try (var ignored = new ScopedSuspendAll(false)) {
            ArtMethodUtils.makeExecutableNonCompilable(target);
            ArtMethodUtils.changeExecutableFlags(target, kAccFastInterpreterToInterpreterInvoke, 0);
            ArtMethodUtils.setExecutableEntryPoint(target, new_entry_point.nativeAddress());
        }
    }

    public enum EntryPointType {
        DIRECT,
        // TODO: What if the code is in jit-cache?
        CURRENT
        // TODO: DYNAMIC
    }

    private static long getEntryPoint(Executable ex, EntryPointType type) {
        return type == EntryPointType.DIRECT ?
                (Modifier.isNative(ex.getModifiers()) ?
                        EntryPoints.getGenericJniTrampoline() :
                        EntryPoints.getToInterpreterBridge()) :
                ArtMethodUtils.getExecutableEntryPoint(ex);
    }

    /**
     * target -> hooker
     * hooker is unchanged
     */
    public static void hook(Executable target, Executable hooker, EntryPointType hooker_type) {
        ensureDeclaringClassInitialized(target);
        ensureDeclaringClassInitialized(hooker);
        hook(target, hooker, getEntryPoint(hooker, hooker_type));
    }

    /**
     * first -> second
     * second -> first
     */
    public static void hookSwap(Executable first, EntryPointType first_type,
                                Executable second, EntryPointType second_type) {
        ensureDeclaringClassInitialized(first);
        ensureDeclaringClassInitialized(second);
        long old_first_entry_point = getEntryPoint(first, first_type);
        hook(first, second, getEntryPoint(second, second_type));
        hook(second, first, old_first_entry_point);
    }

    /**
     * target -> hooker
     * backup -> target
     * hooker is unchanged
     */
    public static void hookBackup(Executable target, EntryPointType target_type,
                                  Executable hooker, EntryPointType hooker_type,
                                  Executable backup) {
        ensureDeclaringClassInitialized(target);
        ensureDeclaringClassInitialized(hooker);
        ensureDeclaringClassInitialized(backup);
        hook(backup, target, getEntryPoint(target, target_type));
        hook(target, hooker, getEntryPoint(hooker, hooker_type));
    }

    public interface HookTransformer {
        void transform(MethodHandle original, EmulatedStackFrame stack) throws Throwable;
    }

    private static final String INVOKER_NAME = Hooks.class.getName() + "$$$Invoker";
    private static final String METHOD_NAME = "invoke";
    private static final String FIELD_NAME = "handle";

    private static void move_result_auto(CodeBuilder b, char type, int reg) {
        switch (type) {
            case 'V' -> { /* nop */ }
            case 'Z', 'B', 'C', 'S', 'I', 'F' -> b.move_result(reg);
            case 'J', 'D' -> b.move_result_wide(reg);
            case 'L' -> b.move_result_object(reg);
            default -> throw shouldNotReachHere();
        }
    }

    private static void return_auto(CodeBuilder b, char type, int reg) {
        switch (type) {
            case 'V' -> b.return_void();
            case 'Z', 'B', 'C', 'S', 'I', 'F' -> b.return_(reg);
            case 'J', 'D' -> b.return_wide(reg);
            case 'L' -> b.return_object(reg);
            default -> throw shouldNotReachHere();
        }
    }

    private static byte[] generateInvoker(MethodType type) {
        TypeId mh_id = TypeId.of(MethodHandle.class);
        TypeId obj_id = TypeId.OBJECT;

        ProtoId proto = ProtoId.of(type);

        TypeId invoker_id = TypeId.of(INVOKER_NAME);
        ClassDef invoker_def = new ClassDef(invoker_id);
        invoker_def.setSuperClass(obj_id);

        FieldId field_id = new FieldId(invoker_id, mh_id, FIELD_NAME);
        invoker_def.getClassData().getStaticFields().add(new EncodedField(
                field_id, ACC_STATIC));

        ProtoId mh_proto = new ProtoId(obj_id, obj_id.array());
        MethodId mh_method = new MethodId(mh_id, mh_proto, "invokeExact");

        int params = proto.getInputRegistersCount();
        var ret_type = proto.getReturnType().getShorty();

        MethodId method_id = new MethodId(invoker_id, proto, METHOD_NAME);
        invoker_def.getClassData().getDirectMethods().add(new EncodedMethod(
                method_id, ACC_STATIC).withCode(/* wide return */ 2, b -> {
                    b.sop(GET_OBJECT, b.v(1), field_id);
                    b.invoke_polymorphic_range(mh_method, proto, params + /* handle */ 1, b.v(1));
                    move_result_auto(b, ret_type, b.l(0));
                    return_auto(b, ret_type, b.l(0));
                }
        ));

        return new Dex(invoker_def).compile();
    }

    private static final WeakReferenceCache<MethodType, byte[]>
            invokers_cache = new WeakReferenceCache<>();

    private static Class<?> loadInvoker(MethodType type) {
        ClassLoader loader = Utils.newEmptyClassLoader(Object.class.getClassLoader());
        var dexfile = DexFileUtils.openDexFile(invokers_cache.get(type, Hooks::generateInvoker));
        return DexFileUtils.loadClass(dexfile, INVOKER_NAME, loader);
    }

    private static final class HookTransformerImpl extends AbstractTransformer {
        private final MethodHandle original;
        private final HookTransformer transformer;

        HookTransformerImpl(MethodHandle original, HookTransformer transformer) {
            this.original = original;
            this.transformer = transformer;
        }

        @Override
        protected void transform(MethodHandle thiz, EmulatedStackFrame stack) throws Throwable {
            stack.setType(original.type());
            transformer.transform(original, stack);
        }
    }

    private static Method initInvoker(MethodType type, HookTransformer transformer) {
        var erased = type.erase(); // TODO: maybe use basic type?
        var invoker_class = loadInvoker(erased);
        var hooker_method = getDeclaredMethod(invoker_class, METHOD_NAME, InvokeAccess.ptypes(erased));
        var backup_handle = MethodHandlesFixes.reinterptetHandle(unreflect(hooker_method), type);
        var impl = new HookTransformerImpl(backup_handle, transformer);
        var handle = Transformers.makeTransformer(erased, impl);
        var handle_field = getDeclaredField(invoker_class, FIELD_NAME);
        AndroidUnsafe.putObject(invoker_class, fieldOffset(handle_field), handle);
        return hooker_method;
    }

    private static MethodType methodTypeOf(Executable ex) {
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

    /**
     * target -> hooker
     * original (parameter of transformer) -> target
     */
    public static void hook(Executable target, EntryPointType target_type,
                            HookTransformer hooker, EntryPointType hooker_type) {
        Objects.requireNonNull(target);
        Objects.requireNonNull(hooker);

        var invoker = initInvoker(methodTypeOf(target), hooker);
        Cleaner.create(target.getDeclaringClass(), () -> Utils.reachabilityFence(invoker));
        hookSwap(target, target_type, invoker, hooker_type);
    }
}

package com.v7878.vmtools;

import static com.v7878.dex.DexConstants.ACC_CONSTRUCTOR;
import static com.v7878.dex.DexConstants.ACC_FINAL;
import static com.v7878.dex.DexConstants.ACC_PRIVATE;
import static com.v7878.dex.DexConstants.ACC_PUBLIC;
import static com.v7878.dex.DexConstants.ACC_STATIC;
import static com.v7878.dex.builder.CodeBuilder.Op.GET_OBJECT;
import static com.v7878.unsafe.ArtModifiers.kAccFastInterpreterToInterpreterInvoke;
import static com.v7878.unsafe.InstructionSet.CURRENT_INSTRUCTION_SET;
import static com.v7878.unsafe.Reflection.fieldOffset;
import static com.v7878.unsafe.Reflection.getArtMethod;
import static com.v7878.unsafe.Reflection.getDeclaredField;
import static com.v7878.unsafe.Reflection.getDeclaredMethod;
import static com.v7878.unsafe.Reflection.unreflect;
import static com.v7878.vmtools._Utils.rawMethodTypeOf;

import com.v7878.dex.DexIO;
import com.v7878.dex.builder.ClassBuilder;
import com.v7878.dex.immutable.ClassDef;
import com.v7878.dex.immutable.Dex;
import com.v7878.dex.immutable.FieldId;
import com.v7878.dex.immutable.MethodId;
import com.v7878.dex.immutable.ProtoId;
import com.v7878.dex.immutable.TypeId;
import com.v7878.foreign.Arena;
import com.v7878.foreign.MemorySegment;
import com.v7878.sun.cleaner.SunCleaner;
import com.v7878.unsafe.AndroidUnsafe;
import com.v7878.unsafe.ArtMethodUtils;
import com.v7878.unsafe.ClassUtils;
import com.v7878.unsafe.DexFileUtils;
import com.v7878.unsafe.NativeCodeBlob;
import com.v7878.unsafe.Utils;
import com.v7878.unsafe.Utils.WeakReferenceCache;
import com.v7878.unsafe.access.InvokeAccess;
import com.v7878.unsafe.invoke.MethodHandlesImpl;
import com.v7878.unsafe.invoke.Transformers;
import com.v7878.vmtools.Runtime.DebugState;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;

public class Hooks {
    static {
        // Classes cannot be loaded and initialized during "SuspendAll"
        {
            ClassUtils.ensureClassInitialized(EntryPoints.class);
            var method = getDeclaredMethod(Runnable.class, "run");
            ArtMethodUtils.makeExecutableNonCompilable(method);
        }

        Runtime.setRuntimeDebugState(DebugState.kNonJavaDebuggable);
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
        SunCleaner.systemCleaner().register(target.getDeclaringClass(), scope::close);
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

    private static final String INVOKER_NAME = Hooks.class.getName() + "$$$Invoker";
    private static final String METHOD_NAME = "invoke";
    private static final String FIELD_NAME = "handle";

    private static byte[] generateInvoker(MethodType type) {
        ProtoId proto = ProtoId.of(type);

        TypeId mh_id = TypeId.of(MethodHandle.class);
        TypeId obj_id = TypeId.OBJECT;

        ProtoId mh_proto = ProtoId.of(obj_id, obj_id.array());
        MethodId mh_method = MethodId.of(mh_id, "invokeExact", mh_proto);

        TypeId invoker_id = TypeId.ofName(INVOKER_NAME);
        FieldId field_id = FieldId.of(invoker_id, FIELD_NAME, mh_id);
        MethodId method_id = MethodId.of(invoker_id, METHOD_NAME, proto);

        int params = proto.countInputRegisters();
        var ret_type = proto.getReturnType().getShorty();

        ClassDef invoker_def = ClassBuilder.build(invoker_id, cb -> cb
                .withSuperClass(obj_id)
                .withFlags(ACC_PUBLIC | ACC_FINAL)
                .withField(fb -> fb
                        .of(field_id)
                        .withFlags(ACC_PRIVATE | ACC_STATIC | ACC_FINAL)
                )
                .withMethod(mb -> mb
                        .of(method_id)
                        .withFlags(ACC_PUBLIC | ACC_STATIC)
                        .withCode(/* wide return */ 2, ib -> {
                            ib.sop(GET_OBJECT, ib.v(1), field_id);
                            ib.invoke_polymorphic_range(mh_method, proto,
                                    params + /* handle */ 1, ib.v(1));
                            ib.move_result_shorty(ret_type, ib.l(0));
                            ib.return_shorty(ret_type, ib.l(0));
                        })
                )
        );

        return DexIO.write(Dex.of(invoker_def));
    }

    private static final WeakReferenceCache<MethodType, byte[]>
            invokers_cache = new WeakReferenceCache<>();

    private static Class<?> loadInvoker(MethodType type) {
        ClassLoader loader = Utils.newEmptyClassLoader(Object.class.getClassLoader());
        var dexfile = DexFileUtils.openDexFile(invokers_cache.get(type, Hooks::generateInvoker));
        return DexFileUtils.loadClass(dexfile, INVOKER_NAME, loader);
    }

    private static Method initInvoker(MethodType type, HookTransformer transformer) {
        var erased = type.erase(); // TODO: maybe use basic type?
        var invoker_class = loadInvoker(erased);
        var hooker_method = getDeclaredMethod(invoker_class, METHOD_NAME, InvokeAccess.ptypes(erased));
        var backup_handle = MethodHandlesImpl.reinterptetHandle(unreflect(hooker_method), type);
        var impl = new HookTransformerImpl(backup_handle, transformer);
        var handle = Transformers.makeTransformer(erased, impl);
        var handle_field = getDeclaredField(invoker_class, FIELD_NAME);
        AndroidUnsafe.putObject(invoker_class, fieldOffset(handle_field), handle);
        return hooker_method;
    }

    /**
     * target -> hooker
     * original (parameter of transformer) -> target
     */
    public static void hook(Executable target, EntryPointType target_type,
                            HookTransformer hooker, EntryPointType hooker_type) {
        Objects.requireNonNull(target);
        Objects.requireNonNull(hooker);

        var invoker = initInvoker(rawMethodTypeOf(target), hooker);
        SunCleaner.systemCleaner().register(target.getDeclaringClass(), () -> Utils.reachabilityFence(invoker));
        hookSwap(target, target_type, invoker, hooker_type);
    }
}

package com.v7878.vmtools;

import static com.v7878.dex.DexConstants.ACC_PUBLIC;
import static com.v7878.dex.DexConstants.ACC_STATIC;
import static com.v7878.dex.bytecode.CodeBuilder.Op.GET_OBJECT;
import static com.v7878.llvm.Core.LLVMAddFunction;
import static com.v7878.llvm.Core.LLVMAppendBasicBlock;
import static com.v7878.llvm.Core.LLVMBuildICmp;
import static com.v7878.llvm.Core.LLVMBuildRet;
import static com.v7878.llvm.Core.LLVMGetParams;
import static com.v7878.llvm.Core.LLVMIntPredicate.LLVMIntEQ;
import static com.v7878.llvm.Core.LLVMPositionBuilderAtEnd;
import static com.v7878.llvm.Types.LLVMTypeRef;
import static com.v7878.llvm.Types.LLVMValueRef;
import static com.v7878.unsafe.AndroidUnsafe.ARRAY_BYTE_BASE_OFFSET;
import static com.v7878.unsafe.AndroidUnsafe.PAGE_SIZE;
import static com.v7878.unsafe.ArtModifiers.kAccCompileDontBother;
import static com.v7878.unsafe.ArtModifiers.kAccFastInterpreterToInterpreterInvoke;
import static com.v7878.unsafe.ArtModifiers.kAccPreCompiled;
import static com.v7878.unsafe.ArtVersion.ART_SDK_INT;
import static com.v7878.unsafe.InstructionSet.CURRENT_INSTRUCTION_SET;
import static com.v7878.unsafe.Reflection.fieldOffset;
import static com.v7878.unsafe.Reflection.getArtMethod;
import static com.v7878.unsafe.Reflection.getDeclaredField;
import static com.v7878.unsafe.Reflection.getDeclaredMethod;
import static com.v7878.unsafe.Reflection.unreflect;
import static com.v7878.unsafe.Utils.shouldNotHappen;
import static com.v7878.unsafe.Utils.shouldNotReachHere;
import static com.v7878.unsafe.foreign.BulkLinker.CallSignature;
import static com.v7878.unsafe.foreign.BulkLinker.CallType.CRITICAL;
import static com.v7878.unsafe.foreign.BulkLinker.LibrarySymbol;
import static com.v7878.unsafe.foreign.BulkLinker.MapType.BOOL;
import static com.v7878.unsafe.foreign.BulkLinker.MapType.LONG_AS_WORD;
import static com.v7878.unsafe.foreign.BulkLinker.MapType.VOID;
import static com.v7878.unsafe.foreign.BulkLinker.processSymbols;
import static com.v7878.unsafe.foreign.LibArt.ART;
import static com.v7878.unsafe.llvm.LLVMBuilder.const_intptr;
import static com.v7878.unsafe.llvm.LLVMTypes.function_t;
import static com.v7878.unsafe.llvm.LLVMTypes.int1_t;
import static com.v7878.unsafe.llvm.LLVMTypes.intptr_t;
import static com.v7878.unsafe.llvm.LLVMUtils.generateFunctionCodeArray;

import android.system.ErrnoException;
import android.system.OsConstants;
import android.util.Log;

import com.v7878.dex.ClassDef;
import com.v7878.dex.Dex;
import com.v7878.dex.EncodedField;
import com.v7878.dex.EncodedMethod;
import com.v7878.dex.FieldId;
import com.v7878.dex.MethodId;
import com.v7878.dex.ProtoId;
import com.v7878.dex.TypeId;
import com.v7878.foreign.Arena;
import com.v7878.foreign.MemorySegment;
import com.v7878.misc.Math;
import com.v7878.r8.annotations.DoNotOptimize;
import com.v7878.r8.annotations.DoNotShrink;
import com.v7878.r8.annotations.DoNotShrinkType;
import com.v7878.unsafe.AndroidUnsafe;
import com.v7878.unsafe.ArtMethodUtils;
import com.v7878.unsafe.ClassUtils;
import com.v7878.unsafe.DexFileUtils;
import com.v7878.unsafe.ExtraMemoryAccess;
import com.v7878.unsafe.NativeCodeBlob;
import com.v7878.unsafe.Utils;
import com.v7878.unsafe.Utils.WeakReferenceCache;
import com.v7878.unsafe.access.InvokeAccess;
import com.v7878.unsafe.invoke.EmulatedStackFrame;
import com.v7878.unsafe.invoke.MethodHandlesFixes;
import com.v7878.unsafe.invoke.Transformers;
import com.v7878.unsafe.invoke.Transformers.AbstractTransformer;
import com.v7878.unsafe.io.IOUtils;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class Hooks {

    private static final int PROT_RX = OsConstants.PROT_READ | OsConstants.PROT_EXEC;
    private static final int PROT_RWX = PROT_RX | OsConstants.PROT_WRITE;

    private static void mprotect(long address, long length, int prot) {
        long end = Math.roundUpUL(address + length, PAGE_SIZE);
        long begin = Math.roundDownUL(address, PAGE_SIZE);
        try {
            IOUtils.mprotect(begin, end - begin, prot);
        } catch (ErrnoException e) {
            throw shouldNotHappen(e);
        }
    }

    @DoNotShrinkType
    @DoNotOptimize
    @SuppressWarnings("SameParameterValue")
    private abstract static class Native {
        @DoNotShrink
        private static final Arena SCOPE = Arena.ofAuto();

        static final MemorySegment CAUSE = SCOPE.allocateFrom("Hook");

        @LibrarySymbol(name = "_ZN3art16ScopedSuspendAllC2EPKcb")
        @CallSignature(type = CRITICAL, ret = VOID, args = {LONG_AS_WORD, LONG_AS_WORD, BOOL})
        abstract void SuspendAll(long thiz, long cause, boolean long_suspend);

        @LibrarySymbol(name = "_ZN3art16ScopedSuspendAllD2Ev")
        @CallSignature(type = CRITICAL, ret = VOID, args = {LONG_AS_WORD})
        abstract void ResumeAll(long thiz);

        static final Native INSTANCE = AndroidUnsafe.allocateInstance(
                processSymbols(SCOPE, Native.class, ART));
    }

    static {
        if (ART_SDK_INT < 33) linker_hook:{
            MemorySegment art_checker = ART.find("_ZN3art11ClassLinker30ShouldUseInterpreterEntrypointEPNS_9ArtMethodEPKv").orElse(null);
            if (art_checker == null) {
                //TODO
                Log.e(Utils.LOG_TAG, "Can`t find ClassLinker::ShouldUseInterpreterEntrypoint, hooks may not work in debug mode");
                break linker_hook;
            }
            final String name = "function";
            byte[] checker = generateFunctionCodeArray((context, module, builder) -> {
                LLVMTypeRef f_type = function_t(int1_t(context), intptr_t(context), intptr_t(context));
                LLVMValueRef function = LLVMAddFunction(module, name, f_type);
                LLVMValueRef[] args = LLVMGetParams(function);

                //LLVMValueRef method = args[0];
                LLVMValueRef quick_code = args[1];

                LLVMPositionBuilderAtEnd(builder, LLVMAppendBasicBlock(function, ""));
                LLVMValueRef test_code_null = LLVMBuildICmp(builder, LLVMIntEQ,
                        quick_code, const_intptr(context, 0), "");

                //TODO
                //if (method->IsNative() || method->IsProxyMethod()) { return false; }
                //if (quick_code == nullptr) { return true; }
                //if (Thread::Current()->IsForceInterpreter()) { return true; }
                //if (Thread::Current()->IsAsyncExceptionPending()) { return true; }
                //return Runtime::Current()->GetClassLinker()->IsQuickToInterpreterBridge(quick_code);

                LLVMBuildRet(builder, test_code_null);
            }, name);

            mprotect(art_checker.nativeAddress(), checker.length, PROT_RWX);
            Native.INSTANCE.SuspendAll(0, Native.CAUSE.nativeAddress(), false);
            try {
                //Copy at once, without exiting to java code
                ExtraMemoryAccess.copyMemory(checker, ARRAY_BYTE_BASE_OFFSET, null, art_checker.nativeAddress(), checker.length);
            } finally {
                Native.INSTANCE.ResumeAll(0);
            }
            mprotect(art_checker.nativeAddress(), checker.length, PROT_RX);
        }
    }

    public static void deoptimize(Executable ex) {
        ClassUtils.ensureClassVisiblyInitialized(ex.getDeclaringClass());
        ArtMethodUtils.changeExecutableFlags(ex, kAccPreCompiled, kAccCompileDontBother);
        long entry_point = Modifier.isNative(ex.getModifiers()) ?
                EntryPoints.getGenericJniTrampoline() :
                EntryPoints.getToInterpreterBridge();
        ArtMethodUtils.setExecutableEntryPoint(ex, entry_point);
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
        //TODO: find a way to ensure that trampoline has
        // same lifetime as declaring class of target executable
        Arena scope = Arena.global();
        MemorySegment new_entry_point = NativeCodeBlob.makeCodeBlob(scope,
                getTrampolineArray(getArtMethod(hooker), hooker_entry_point))[0];
        ArtMethodUtils.makeExecutableNonCompilable(target);
        ArtMethodUtils.changeExecutableFlags(target, kAccFastInterpreterToInterpreterInvoke, 0);
        ArtMethodUtils.setExecutableEntryPoint(target, new_entry_point.nativeAddress());
    }

    public enum EntryPointType {
        DIRECT, CURRENT // TODO: DYNAMIC
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
        ClassUtils.ensureClassVisiblyInitialized(target.getDeclaringClass());
        ClassUtils.ensureClassVisiblyInitialized(hooker.getDeclaringClass());
        hook(target, hooker, getEntryPoint(hooker, hooker_type));
    }

    /**
     * first -> second
     * second -> first
     */
    public static void hookSwap(Executable first, EntryPointType first_type,
                                Executable second, EntryPointType second_type) {
        ClassUtils.ensureClassVisiblyInitialized(first.getDeclaringClass());
        ClassUtils.ensureClassVisiblyInitialized(second.getDeclaringClass());
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
        ClassUtils.ensureClassVisiblyInitialized(target.getDeclaringClass());
        ClassUtils.ensureClassVisiblyInitialized(hooker.getDeclaringClass());
        ClassUtils.ensureClassVisiblyInitialized(backup.getDeclaringClass());
        hook(backup, target, getEntryPoint(target, target_type));
        hook(target, hooker, getEntryPoint(hooker, hooker_type));
    }

    public interface HookTransformer {
        void transform(MethodHandle original, EmulatedStackFrame stack) throws Throwable;
    }

    private static final String INVOKER_NAME = Hooks.class.getName() + "$$$Invoker";
    private static final String METHOD_NAME = "invoke";
    private static final String FIELD_NAME = "handle";

    private static byte[] generateInvoker(MethodType type) {
        TypeId mh_id = TypeId.of(MethodHandle.class);
        TypeId obj_id = TypeId.OBJECT;

        ProtoId proto = ProtoId.of(type);

        TypeId invoker_id = TypeId.of(INVOKER_NAME);
        ClassDef invoker_def = new ClassDef(invoker_id);
        invoker_def.setSuperClass(obj_id);

        FieldId field_id = new FieldId(invoker_id, mh_id, FIELD_NAME);
        invoker_def.getClassData().getStaticFields().add(new EncodedField(
                field_id, ACC_PUBLIC | ACC_STATIC));

        ProtoId mh_proto = new ProtoId(obj_id, obj_id.array());
        MethodId mh_method = new MethodId(mh_id, mh_proto, "invokeExact");

        int params = proto.getInputRegistersCount() + /* handle */ 1;

        MethodId method_id = new MethodId(invoker_id, proto, METHOD_NAME);
        invoker_def.getClassData().getDirectMethods().add(new EncodedMethod(
                method_id, ACC_PUBLIC | ACC_STATIC).withCode(1, b -> {
                    b.sop(GET_OBJECT, b.v(0), field_id);
                    b.invoke_polymorphic_range(mh_method, proto, params, b.v(0));
                    switch (proto.getReturnType().getShorty()) {
                        case 'V' -> b.return_void();
                        case 'Z', 'B', 'C', 'S', 'I', 'F' -> {
                            b.move_result(b.l(0));
                            b.return_(b.l(0));
                        }
                        case 'J', 'D' -> {
                            b.move_result_wide(b.l(0));
                            b.return_wide(b.l(0));
                        }
                        case 'L' -> {
                            b.move_result_object(b.l(0));
                            b.return_object(b.l(0));
                        }
                        default -> throw shouldNotReachHere();
                    }
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
        var invoker = loadInvoker(erased);
        var m_hooker = getDeclaredMethod(invoker, METHOD_NAME, InvokeAccess.ptypes(erased));
        var original = MethodHandlesFixes.reinterptetHandle(unreflect(m_hooker), type);
        var impl = new HookTransformerImpl(original, transformer);
        var handle = Transformers.makeTransformer(erased, impl);
        var f_handle = getDeclaredField(invoker, FIELD_NAME);
        AndroidUnsafe.putObject(invoker, fieldOffset(f_handle), handle);
        return m_hooker;
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

        var type = methodTypeOf(target);
        var invoker = initInvoker(type, hooker);
        hookSwap(target, target_type, invoker, hooker_type);
    }
}

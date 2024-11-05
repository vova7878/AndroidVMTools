package com.v7878.vmtools;

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
import static com.v7878.unsafe.Reflection.getArtMethod;
import static com.v7878.unsafe.Utils.shouldNotHappen;
import static com.v7878.unsafe.foreign.LibArt.ART;
import static com.v7878.unsafe.llvm.LLVMBuilder.const_intptr;
import static com.v7878.unsafe.llvm.LLVMTypes.function_t;
import static com.v7878.unsafe.llvm.LLVMTypes.int1_t;
import static com.v7878.unsafe.llvm.LLVMTypes.intptr_t;
import static com.v7878.unsafe.llvm.LLVMUtils.generateFunctionCodeArray;

import android.system.ErrnoException;
import android.system.OsConstants;
import android.util.Log;

import com.v7878.foreign.Arena;
import com.v7878.foreign.MemorySegment;
import com.v7878.misc.Math;
import com.v7878.unsafe.ArtMethodUtils;
import com.v7878.unsafe.ClassUtils;
import com.v7878.unsafe.ExtraMemoryAccess;
import com.v7878.unsafe.NativeCodeBlob;
import com.v7878.unsafe.Utils;
import com.v7878.unsafe.io.IOUtils;

import java.lang.reflect.Executable;
import java.lang.reflect.Modifier;
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
            //Copy at once, without exiting to java code
            ExtraMemoryAccess.copyMemory(checker, ARRAY_BYTE_BASE_OFFSET, null, art_checker.nativeAddress(), checker.length);
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
}

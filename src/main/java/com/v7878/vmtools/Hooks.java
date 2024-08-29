package com.v7878.vmtools;

import static com.v7878.unsafe.ArtMethodUtils.kAccCompileDontBother;
import static com.v7878.unsafe.ArtMethodUtils.kAccFastInterpreterToInterpreterInvoke;
import static com.v7878.unsafe.ArtMethodUtils.kAccPreCompiled;
import static com.v7878.unsafe.InstructionSet.CURRENT_INSTRUCTION_SET;
import static com.v7878.unsafe.Reflection.getArtMethod;

import com.v7878.foreign.Arena;
import com.v7878.foreign.MemorySegment;
import com.v7878.unsafe.ArtMethodUtils;
import com.v7878.unsafe.ClassUtils;
import com.v7878.unsafe.NativeCodeBlob;

import java.lang.reflect.Executable;
import java.lang.reflect.Modifier;
import java.util.Objects;

public class Hooks {
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
                    0x48, e[0], e[1], e[2], e[3], e[4], e[5], e[6], e[7],
                    0x57,
                    0x48, m[0], m[1], m[2], m[3], m[4], m[5], m[6], m[7],
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

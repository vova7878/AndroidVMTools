package com.v7878.vmtools;

import static com.v7878.unsafe.foreign.BulkLinker.CallType.CRITICAL;
import static com.v7878.unsafe.foreign.BulkLinker.MapType.BOOL;
import static com.v7878.unsafe.foreign.BulkLinker.MapType.LONG_AS_WORD;
import static com.v7878.unsafe.foreign.BulkLinker.MapType.VOID;
import static com.v7878.unsafe.foreign.LibArt.ART;

import com.v7878.foreign.Arena;
import com.v7878.foreign.MemorySegment;
import com.v7878.r8.annotations.DoNotOptimize;
import com.v7878.r8.annotations.DoNotShrink;
import com.v7878.r8.annotations.DoNotShrinkType;
import com.v7878.unsafe.Utils.FineClosable;
import com.v7878.unsafe.foreign.BulkLinker;
import com.v7878.unsafe.foreign.BulkLinker.CallSignature;
import com.v7878.unsafe.foreign.BulkLinker.LibrarySymbol;

public class ScopedSuspendAll implements FineClosable {
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

        static final Native INSTANCE = BulkLinker.generateImpl(SCOPE, Native.class, ART);
    }

    public ScopedSuspendAll(boolean long_suspend) {
        Native.INSTANCE.SuspendAll(0, Native.CAUSE.nativeAddress(), long_suspend);
    }

    @Override
    public void close() {
        Native.INSTANCE.ResumeAll(0);
    }
}

package com.v7878.vmtools;

import static com.v7878.unsafe.ArtVersion.ART_SDK_INT;
import static com.v7878.unsafe.foreign.BulkLinker.CallType.CRITICAL;
import static com.v7878.unsafe.foreign.BulkLinker.MapType.INT;
import static com.v7878.unsafe.foreign.BulkLinker.MapType.LONG_AS_WORD;
import static com.v7878.unsafe.foreign.BulkLinker.MapType.VOID;
import static com.v7878.unsafe.foreign.BulkLinker.processSymbols;
import static com.v7878.unsafe.foreign.LibArt.ART;
import static com.v7878.vmtools.RuntimeUtils.DebugState.kNonJavaDebuggable;

import com.v7878.foreign.Arena;
import com.v7878.r8.annotations.DoNotOptimize;
import com.v7878.r8.annotations.DoNotShrink;
import com.v7878.r8.annotations.DoNotShrinkType;
import com.v7878.unsafe.AndroidUnsafe;
import com.v7878.unsafe.JNIUtils;
import com.v7878.unsafe.foreign.BulkLinker.CallSignature;
import com.v7878.unsafe.foreign.BulkLinker.Conditions;
import com.v7878.unsafe.foreign.BulkLinker.LibrarySymbol;

import java.util.Objects;

public class RuntimeUtils {
    public enum DebugState {
        // This doesn't support any debug features / method tracing. This is the expected state usually.
        kNonJavaDebuggable,
        // This supports method tracing and a restricted set of debug features (for ex: redefinition
        // isn't supported). We transition to this state when method tracing has started or when the
        // debugger was attached and transition back to NonDebuggable once the tracing has stopped /
        // the debugger agent has detached..
        kJavaDebuggable,
        // The runtime was started as a debuggable runtime. This allows us to support the extended set
        // of debug features (for ex: redefinition). We never transition out of this state.
        kJavaDebuggableAtInit
    }

    @DoNotShrinkType
    @DoNotOptimize
    private abstract static class Native {
        @DoNotShrink
        private static final Arena SCOPE = Arena.ofAuto();

        @LibrarySymbol(conditions = @Conditions(art_api = {34, 35, 36}),
                name = "_ZN3art7Runtime20SetRuntimeDebugStateENS0_17RuntimeDebugStateE")
        @LibrarySymbol(conditions = @Conditions(art_api = {26, 27, 28, 29, 30, 31, 32, 33}),
                name = "_ZN3art7Runtime17SetJavaDebuggableEb")
        @CallSignature(type = CRITICAL, ret = VOID, args = {LONG_AS_WORD, INT})
        abstract void SetRuntimeDebugState(long runtime, int state);

        @LibrarySymbol(name = "_ZN3art7Runtime19DeoptimizeBootImageEv")
        @CallSignature(type = CRITICAL, ret = VOID, args = {LONG_AS_WORD})
        abstract void DeoptimizeBootImage(long runtime);

        static final Native INSTANCE = AndroidUnsafe.allocateInstance(
                processSymbols(SCOPE, Native.class, ART));
    }

    public static void setRuntimeDebugState(DebugState state) {
        Objects.requireNonNull(state);
        int value;
        if (ART_SDK_INT <= 33) {
            value = state == kNonJavaDebuggable ? 0 : 1;
        } else {
            value = state.ordinal();
        }
        Native.INSTANCE.SetRuntimeDebugState(JNIUtils.getRuntimePtr(), value);
    }

    public static void DeoptimizeBootImage() {
        var instance = Native.INSTANCE;
        try (var ignored = new ScopedSuspendAll(false)) {
            instance.DeoptimizeBootImage(JNIUtils.getRuntimePtr());
        }
    }
}

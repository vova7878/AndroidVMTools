package com.v7878.ti;

import static com.v7878.foreign.MemoryLayout.PathElement.groupElement;
import static com.v7878.foreign.MemoryLayout.paddedStructLayout;
import static com.v7878.foreign.MemoryLayout.structLayout;
import static com.v7878.foreign.ValueLayout.ADDRESS;
import static com.v7878.foreign.ValueLayout.JAVA_BYTE;
import static com.v7878.foreign.ValueLayout.JAVA_DOUBLE;
import static com.v7878.foreign.ValueLayout.JAVA_FLOAT;
import static com.v7878.foreign.ValueLayout.JAVA_INT;
import static com.v7878.foreign.ValueLayout.JAVA_LONG;
import static com.v7878.foreign.ValueLayout.JAVA_LONG_UNALIGNED;
import static com.v7878.llvm.Core.LLVMAddFunction;
import static com.v7878.llvm.Core.LLVMAppendBasicBlock;
import static com.v7878.llvm.Core.LLVMBuildRet;
import static com.v7878.llvm.Core.LLVMBuildStore;
import static com.v7878.llvm.Core.LLVMGetParams;
import static com.v7878.llvm.Core.LLVMPositionBuilderAtEnd;
import static com.v7878.llvm.Core.LLVMSetAlignment;
import static com.v7878.ti.JVMTIConstants.JVMTI_ERROR_ABSENT_INFORMATION;
import static com.v7878.ti.JVMTIConstants.JVMTI_ERROR_ACCESS_DENIED;
import static com.v7878.ti.JVMTIConstants.JVMTI_ERROR_CIRCULAR_CLASS_DEFINITION;
import static com.v7878.ti.JVMTIConstants.JVMTI_ERROR_CLASS_LOADER_UNSUPPORTED;
import static com.v7878.ti.JVMTIConstants.JVMTI_ERROR_CLASS_NOT_PREPARED;
import static com.v7878.ti.JVMTIConstants.JVMTI_ERROR_DUPLICATE;
import static com.v7878.ti.JVMTIConstants.JVMTI_ERROR_FAILS_VERIFICATION;
import static com.v7878.ti.JVMTIConstants.JVMTI_ERROR_ILLEGAL_ARGUMENT;
import static com.v7878.ti.JVMTIConstants.JVMTI_ERROR_INTERNAL;
import static com.v7878.ti.JVMTIConstants.JVMTI_ERROR_INTERRUPT;
import static com.v7878.ti.JVMTIConstants.JVMTI_ERROR_INVALID_CLASS;
import static com.v7878.ti.JVMTIConstants.JVMTI_ERROR_INVALID_CLASS_FORMAT;
import static com.v7878.ti.JVMTIConstants.JVMTI_ERROR_INVALID_ENVIRONMENT;
import static com.v7878.ti.JVMTIConstants.JVMTI_ERROR_INVALID_EVENT_TYPE;
import static com.v7878.ti.JVMTIConstants.JVMTI_ERROR_INVALID_FIELDID;
import static com.v7878.ti.JVMTIConstants.JVMTI_ERROR_INVALID_LOCATION;
import static com.v7878.ti.JVMTIConstants.JVMTI_ERROR_INVALID_METHODID;
import static com.v7878.ti.JVMTIConstants.JVMTI_ERROR_INVALID_MONITOR;
import static com.v7878.ti.JVMTIConstants.JVMTI_ERROR_INVALID_OBJECT;
import static com.v7878.ti.JVMTIConstants.JVMTI_ERROR_INVALID_PRIORITY;
import static com.v7878.ti.JVMTIConstants.JVMTI_ERROR_INVALID_SLOT;
import static com.v7878.ti.JVMTIConstants.JVMTI_ERROR_INVALID_THREAD;
import static com.v7878.ti.JVMTIConstants.JVMTI_ERROR_INVALID_THREAD_GROUP;
import static com.v7878.ti.JVMTIConstants.JVMTI_ERROR_INVALID_TYPESTATE;
import static com.v7878.ti.JVMTIConstants.JVMTI_ERROR_MUST_POSSESS_CAPABILITY;
import static com.v7878.ti.JVMTIConstants.JVMTI_ERROR_NAMES_DONT_MATCH;
import static com.v7878.ti.JVMTIConstants.JVMTI_ERROR_NATIVE_METHOD;
import static com.v7878.ti.JVMTIConstants.JVMTI_ERROR_NONE;
import static com.v7878.ti.JVMTIConstants.JVMTI_ERROR_NOT_AVAILABLE;
import static com.v7878.ti.JVMTIConstants.JVMTI_ERROR_NOT_FOUND;
import static com.v7878.ti.JVMTIConstants.JVMTI_ERROR_NOT_MONITOR_OWNER;
import static com.v7878.ti.JVMTIConstants.JVMTI_ERROR_NO_MORE_FRAMES;
import static com.v7878.ti.JVMTIConstants.JVMTI_ERROR_NULL_POINTER;
import static com.v7878.ti.JVMTIConstants.JVMTI_ERROR_OPAQUE_FRAME;
import static com.v7878.ti.JVMTIConstants.JVMTI_ERROR_OUT_OF_MEMORY;
import static com.v7878.ti.JVMTIConstants.JVMTI_ERROR_THREAD_NOT_ALIVE;
import static com.v7878.ti.JVMTIConstants.JVMTI_ERROR_THREAD_NOT_SUSPENDED;
import static com.v7878.ti.JVMTIConstants.JVMTI_ERROR_THREAD_SUSPENDED;
import static com.v7878.ti.JVMTIConstants.JVMTI_ERROR_TYPE_MISMATCH;
import static com.v7878.ti.JVMTIConstants.JVMTI_ERROR_UNATTACHED_THREAD;
import static com.v7878.ti.JVMTIConstants.JVMTI_ERROR_UNMODIFIABLE_CLASS;
import static com.v7878.ti.JVMTIConstants.JVMTI_ERROR_UNSUPPORTED_REDEFINITION_CLASS_MODIFIERS_CHANGED;
import static com.v7878.ti.JVMTIConstants.JVMTI_ERROR_UNSUPPORTED_REDEFINITION_HIERARCHY_CHANGED;
import static com.v7878.ti.JVMTIConstants.JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_ADDED;
import static com.v7878.ti.JVMTIConstants.JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_DELETED;
import static com.v7878.ti.JVMTIConstants.JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_MODIFIERS_CHANGED;
import static com.v7878.ti.JVMTIConstants.JVMTI_ERROR_UNSUPPORTED_REDEFINITION_SCHEMA_CHANGED;
import static com.v7878.ti.JVMTIConstants.JVMTI_ERROR_UNSUPPORTED_VERSION;
import static com.v7878.ti.JVMTIConstants.JVMTI_ERROR_WRONG_PHASE;
import static com.v7878.ti.JVMTIConstants.JVMTI_VERSION;
import static com.v7878.ti.JVMTIConstants.JVMTI_VERSION_1_2;
import static com.v7878.unsafe.AndroidUnsafe.putIntN;
import static com.v7878.unsafe.AndroidUnsafe.putWordN;
import static com.v7878.unsafe.ArtVersion.ART_SDK_INT;
import static com.v7878.unsafe.JNIUtils.JNI_EVERSION;
import static com.v7878.unsafe.JNIUtils.JNI_OK;
import static com.v7878.unsafe.JNIUtils.getJNIInvokeInterfaceLookup;
import static com.v7878.unsafe.JNIUtils.getJavaVMPtr;
import static com.v7878.unsafe.foreign.BulkLinker.CallType.CRITICAL;
import static com.v7878.unsafe.foreign.BulkLinker.CallType.NATIVE_STATIC_OMIT_ENV;
import static com.v7878.unsafe.foreign.BulkLinker.MapType.BOOL;
import static com.v7878.unsafe.foreign.BulkLinker.MapType.DOUBLE;
import static com.v7878.unsafe.foreign.BulkLinker.MapType.FLOAT;
import static com.v7878.unsafe.foreign.BulkLinker.MapType.INT;
import static com.v7878.unsafe.foreign.BulkLinker.MapType.LONG;
import static com.v7878.unsafe.foreign.BulkLinker.MapType.LONG_AS_WORD;
import static com.v7878.unsafe.foreign.BulkLinker.MapType.OBJECT;
import static com.v7878.unsafe.foreign.ExtraLayouts.WORD;
import static com.v7878.unsafe.llvm.LLVMBuilder.const_int128;
import static com.v7878.unsafe.llvm.LLVMBuilder.const_int32;
import static com.v7878.unsafe.llvm.LLVMTypes.function_t;
import static com.v7878.unsafe.llvm.LLVMTypes.int128_t;
import static com.v7878.unsafe.llvm.LLVMTypes.int32_t;
import static com.v7878.unsafe.llvm.LLVMTypes.intptr_t;
import static com.v7878.unsafe.llvm.LLVMTypes.ptr_t;
import static com.v7878.unsafe.llvm.LLVMUtils.generateFunctionCodeSegment;

import android.util.Pair;

import com.v7878.foreign.AddressLayout;
import com.v7878.foreign.Arena;
import com.v7878.foreign.GroupLayout;
import com.v7878.foreign.MemoryLayout;
import com.v7878.foreign.MemorySegment;
import com.v7878.foreign.SymbolLookup;
import com.v7878.llvm.Types.LLVMTypeRef;
import com.v7878.llvm.Types.LLVMValueRef;
import com.v7878.r8.annotations.AlwaysInline;
import com.v7878.r8.annotations.DoNotOptimize;
import com.v7878.r8.annotations.DoNotShrink;
import com.v7878.r8.annotations.DoNotShrinkType;
import com.v7878.unsafe.AndroidUnsafe;
import com.v7878.unsafe.ApiSensitive;
import com.v7878.unsafe.JNIUtils;
import com.v7878.unsafe.VM;
import com.v7878.unsafe.access.JavaForeignAccess;
import com.v7878.unsafe.foreign.BulkLinker;
import com.v7878.unsafe.foreign.BulkLinker.CallSignature;
import com.v7878.unsafe.foreign.BulkLinker.LibrarySymbol;
import com.v7878.unsafe.foreign.LibDL;
import com.v7878.unsafe.foreign.RawNativeLibraries;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Optional;

public final class JVMTI {
    private JVMTI() {
    }

    @DoNotShrink
    static final Arena JVMTI_SCOPE = Arena.ofAuto();

    public static final GroupLayout JVMTI_INTERFACE_LAYOUT = structLayout(
            ADDRESS.withName("reserved1"),
            ADDRESS.withName("SetEventNotificationMode"),
            ADDRESS.withName("reserved3"),
            ADDRESS.withName("GetAllThreads"),
            ADDRESS.withName("SuspendThread"),
            ADDRESS.withName("ResumeThread"),
            ADDRESS.withName("StopThread"),
            ADDRESS.withName("InterruptThread"),
            ADDRESS.withName("GetThreadInfo"),
            ADDRESS.withName("GetOwnedMonitorInfo"),
            ADDRESS.withName("GetCurrentContendedMonitor"),
            ADDRESS.withName("RunAgentThread"),
            ADDRESS.withName("GetTopThreadGroups"),
            ADDRESS.withName("GetThreadGroupInfo"),
            ADDRESS.withName("GetThreadGroupChildren"),
            ADDRESS.withName("GetFrameCount"),
            ADDRESS.withName("GetThreadState"),
            ADDRESS.withName("GetCurrentThread"),
            ADDRESS.withName("GetFrameLocation"),
            ADDRESS.withName("NotifyFramePop"),
            ADDRESS.withName("GetLocalObject"),
            ADDRESS.withName("GetLocalInt"),
            ADDRESS.withName("GetLocalLong"),
            ADDRESS.withName("GetLocalFloat"),
            ADDRESS.withName("GetLocalDouble"),
            ADDRESS.withName("SetLocalObject"),
            ADDRESS.withName("SetLocalInt"),
            ADDRESS.withName("SetLocalLong"),
            ADDRESS.withName("SetLocalFloat"),
            ADDRESS.withName("SetLocalDouble"),
            ADDRESS.withName("CreateRawMonitor"),
            ADDRESS.withName("DestroyRawMonitor"),
            ADDRESS.withName("RawMonitorEnter"),
            ADDRESS.withName("RawMonitorExit"),
            ADDRESS.withName("RawMonitorWait"),
            ADDRESS.withName("RawMonitorNotify"),
            ADDRESS.withName("RawMonitorNotifyAll"),
            ADDRESS.withName("SetBreakpoint"),
            ADDRESS.withName("ClearBreakpoint"),
            ADDRESS.withName("reserved40"),
            ADDRESS.withName("SetFieldAccessWatch"),
            ADDRESS.withName("ClearFieldAccessWatch"),
            ADDRESS.withName("SetFieldModificationWatch"),
            ADDRESS.withName("ClearFieldModificationWatch"),
            ADDRESS.withName("IsModifiableClass"),
            ADDRESS.withName("Allocate"),
            ADDRESS.withName("Deallocate"),
            ADDRESS.withName("GetClassSignature"),
            ADDRESS.withName("GetClassStatus"),
            ADDRESS.withName("GetSourceFileName"),
            ADDRESS.withName("GetClassModifiers"),
            ADDRESS.withName("GetClassMethods"),
            ADDRESS.withName("GetClassFields"),
            ADDRESS.withName("GetImplementedInterfaces"),
            ADDRESS.withName("IsInterface"),
            ADDRESS.withName("IsArrayClass"),
            ADDRESS.withName("GetClassLoader"),
            ADDRESS.withName("GetObjectHashCode"),
            ADDRESS.withName("GetObjectMonitorUsage"),
            ADDRESS.withName("GetFieldName"),
            ADDRESS.withName("GetFieldDeclaringClass"),
            ADDRESS.withName("GetFieldModifiers"),
            ADDRESS.withName("IsFieldSynthetic"),
            ADDRESS.withName("GetMethodName"),
            ADDRESS.withName("GetMethodDeclaringClass"),
            ADDRESS.withName("GetMethodModifiers"),
            ADDRESS.withName("reserved67"),
            ADDRESS.withName("GetMaxLocals"),
            ADDRESS.withName("GetArgumentsSize"),
            ADDRESS.withName("GetLineNumberTable"),
            ADDRESS.withName("GetMethodLocation"),
            ADDRESS.withName("GetLocalVariableTable"),
            ADDRESS.withName("SetNativeMethodPrefix"),
            ADDRESS.withName("SetNativeMethodPrefixes"),
            ADDRESS.withName("GetBytecodes"),
            ADDRESS.withName("IsMethodNative"),
            ADDRESS.withName("IsMethodSynthetic"),
            ADDRESS.withName("GetLoadedClasses"),
            ADDRESS.withName("GetClassLoaderClasses"),
            ADDRESS.withName("PopFrame"),
            ADDRESS.withName("ForceEarlyReturnObject"),
            ADDRESS.withName("ForceEarlyReturnInt"),
            ADDRESS.withName("ForceEarlyReturnLong"),
            ADDRESS.withName("ForceEarlyReturnFloat"),
            ADDRESS.withName("ForceEarlyReturnDouble"),
            ADDRESS.withName("ForceEarlyReturnVoid"),
            ADDRESS.withName("RedefineClasses"),
            ADDRESS.withName("GetVersionNumber"),
            ADDRESS.withName("GetCapabilities"),
            ADDRESS.withName("GetSourceDebugExtension"),
            ADDRESS.withName("IsMethodObsolete"),
            ADDRESS.withName("SuspendThreadList"),
            ADDRESS.withName("ResumeThreadList"),
            ADDRESS.withName("reserved94"),
            ADDRESS.withName("reserved95"),
            ADDRESS.withName("reserved96"),
            ADDRESS.withName("reserved97"),
            ADDRESS.withName("reserved98"),
            ADDRESS.withName("reserved99"),
            ADDRESS.withName("GetAllStackTraces"),
            ADDRESS.withName("GetThreadListStackTraces"),
            ADDRESS.withName("GetThreadLocalStorage"),
            ADDRESS.withName("SetThreadLocalStorage"),
            ADDRESS.withName("GetStackTrace"),
            ADDRESS.withName("reserved105"),
            ADDRESS.withName("GetTag"),
            ADDRESS.withName("SetTag"),
            ADDRESS.withName("ForceGarbageCollection"),
            ADDRESS.withName("IterateOverObjectsReachableFromObject"),
            ADDRESS.withName("IterateOverReachableObjects"),
            ADDRESS.withName("IterateOverHeap"),
            ADDRESS.withName("IterateOverInstancesOfClass"),
            ADDRESS.withName("reserved113"),
            ADDRESS.withName("GetObjectsWithTags"),
            ADDRESS.withName("FollowReferences"),
            ADDRESS.withName("IterateThroughHeap"),
            ADDRESS.withName("reserved117"),
            ADDRESS.withName("reserved118"),
            ADDRESS.withName("reserved119"),
            ADDRESS.withName("SetJNIFunctionTable"),
            ADDRESS.withName("GetJNIFunctionTable"),
            ADDRESS.withName("SetEventCallbacks"),
            ADDRESS.withName("GenerateEvents"),
            ADDRESS.withName("GetExtensionFunctions"),
            ADDRESS.withName("GetExtensionEvents"),
            ADDRESS.withName("SetExtensionEventCallback"),
            ADDRESS.withName("DisposeEnvironment"),
            ADDRESS.withName("GetErrorName"),
            ADDRESS.withName("GetJLocationFormat"),
            ADDRESS.withName("GetSystemProperties"),
            ADDRESS.withName("GetSystemProperty"),
            ADDRESS.withName("SetSystemProperty"),
            ADDRESS.withName("GetPhase"),
            ADDRESS.withName("GetCurrentThreadCpuTimerInfo"),
            ADDRESS.withName("GetCurrentThreadCpuTime"),
            ADDRESS.withName("GetThreadCpuTimerInfo"),
            ADDRESS.withName("GetThreadCpuTime"),
            ADDRESS.withName("GetTimerInfo"),
            ADDRESS.withName("GetTime"),
            ADDRESS.withName("GetPotentialCapabilities"),
            ADDRESS.withName("reserved141"),
            ADDRESS.withName("AddCapabilities"),
            ADDRESS.withName("RelinquishCapabilities"),
            ADDRESS.withName("GetAvailableProcessors"),
            ADDRESS.withName("GetClassVersionNumbers"),
            ADDRESS.withName("GetConstantPool"),
            ADDRESS.withName("GetEnvironmentLocalStorage"),
            ADDRESS.withName("SetEnvironmentLocalStorage"),
            ADDRESS.withName("AddToBootstrapClassLoaderSearch"),
            ADDRESS.withName("SetVerboseFlag"),
            ADDRESS.withName("AddToSystemClassLoaderSearch"),
            ADDRESS.withName("RetransformClasses"),
            ADDRESS.withName("GetOwnedMonitorStackDepthInfo"),
            ADDRESS.withName("GetObjectSize"),
            ADDRESS.withName("GetLocalInstance")
    );
    public static final AddressLayout jvmtiEnv_LAYOUT
            = ADDRESS.withTargetLayout(JVMTI_INTERFACE_LAYOUT);

    private static final String JVMTI_NAME = VM.isDebugVMLibrary() ? "libopenjdkjvmtid.so" : "libopenjdkjvmti.so";
    @ApiSensitive
    public static final SymbolLookup JVMTI = JavaForeignAccess.libraryLookup(
            RawNativeLibraries.cload(JVMTI_NAME, LibDL.ART_CALLER), JVMTI_SCOPE);

    @DoNotShrinkType
    @DoNotOptimize
    private abstract static class Init {
        @LibrarySymbol(name = "GetEnv")
        @CallSignature(type = CRITICAL, ret = INT, args = {LONG_AS_WORD, LONG_AS_WORD, INT})
        abstract int GetEnv(long vm, long env, int version);

        @LibrarySymbol(name = "ArtPlugin_Initialize")
        @CallSignature(type = NATIVE_STATIC_OMIT_ENV, ret = BOOL, args = {})
        abstract boolean Initialize();

        @LibrarySymbol(name = "ArtPlugin_Deinitialize")
        @CallSignature(type = NATIVE_STATIC_OMIT_ENV, ret = BOOL, args = {})
        abstract boolean Deinitialize();

        static final Init INSTANCE = AndroidUnsafe.allocateInstance(
                BulkLinker.processSymbols(JVMTI_SCOPE, Init.class,
                        JVMTI.or(getJNIInvokeInterfaceLookup())));
    }

    private static final long JVMTI_ENV;

    static {
        final int kArtTiVersion = JVMTI_VERSION_1_2 | 0x40000000;
        int version = ART_SDK_INT <= 27 ? JVMTI_VERSION : kArtTiVersion;
        long JVM = getJavaVMPtr();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ptr = arena.allocate(ADDRESS);
            int status = Init.INSTANCE.GetEnv(JVM, ptr.nativeAddress(), version);
            if (status == JNI_EVERSION) {
                boolean loaded = Init.INSTANCE.Initialize();
                assert loaded;
                JavaForeignAccess.addOrCleanupIfFail(JVMTI_SCOPE.scope(), () -> {
                    boolean unloaded = Init.INSTANCE.Deinitialize();
                    assert unloaded;
                });
                status = Init.INSTANCE.GetEnv(JVM, ptr.nativeAddress(), version);
            }
            if (status != JNI_OK) {
                throw new IllegalStateException("Can`t get env: " + status);
            }
            JVMTI_ENV = ptr.get(ADDRESS, 0).nativeAddress();
        }
        ForceAllPotentialCapabilities();
    }

    public static MemorySegment getJVMTIEnvPtr() {
        class Holder {
            static final MemorySegment env = MemorySegment
                    .ofAddress(JVMTI_ENV).reinterpret(JVMTI_SCOPE, null);
        }
        return Holder.env;
    }

    public static MemorySegment getJVMTIInterface() {
        class Holder {
            static final MemorySegment jvmti_interface = JVMTI
                    .findOrThrow("_ZN12openjdkjvmti15gJvmtiInterfaceE")
                    .reinterpret(JVMTI_INTERFACE_LAYOUT.byteSize(), JVMTI_SCOPE, null);
        }
        return Holder.jvmti_interface;
    }

    public static MemorySegment getJVMTIInterfaceFunction(String name) {
        return getJVMTIInterface().get(ADDRESS,
                JVMTI_INTERFACE_LAYOUT.byteOffset(groupElement(name)));
    }

    public static SymbolLookup getJVMTIInterfaceLookup() {
        return (name) -> {
            try {
                return Optional.of(getJVMTIInterfaceFunction(name));
            } catch (Throwable th) {
                return Optional.empty();
            }
        };
    }

    public static class JVMTICapabilities {
        public static MemoryLayout LAYOUT = structLayout(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);

        public boolean can_tag_objects;
        public boolean can_generate_field_modification_events;
        public boolean can_generate_field_access_events;
        public boolean can_get_bytecodes;
        public boolean can_get_synthetic_attribute;
        public boolean can_get_owned_monitor_info;
        public boolean can_get_current_contended_monitor;
        public boolean can_get_monitor_info;
        public boolean can_pop_frame;
        public boolean can_redefine_classes;
        public boolean can_signal_thread;
        public boolean can_get_source_file_name;
        public boolean can_get_line_numbers;
        public boolean can_get_source_debug_extension;
        public boolean can_access_local_variables;
        public boolean can_maintain_original_method_order;
        public boolean can_generate_single_step_events;
        public boolean can_generate_exception_events;
        public boolean can_generate_frame_pop_events;
        public boolean can_generate_breakpoint_events;
        public boolean can_suspend;
        public boolean can_redefine_any_class;
        public boolean can_get_current_thread_cpu_time;
        public boolean can_get_thread_cpu_time;
        public boolean can_generate_method_entry_events;
        public boolean can_generate_method_exit_events;
        public boolean can_generate_all_class_hook_events;
        public boolean can_generate_compiled_method_load_events;
        public boolean can_generate_monitor_events;
        public boolean can_generate_vm_object_alloc_events;
        public boolean can_generate_native_method_bind_events;
        public boolean can_generate_garbage_collection_events;
        public boolean can_generate_object_free_events;
        public boolean can_force_early_return;
        public boolean can_get_owned_monitor_stack_depth_info;
        public boolean can_get_constant_pool;
        public boolean can_set_native_method_prefix;
        public boolean can_retransform_classes;
        public boolean can_retransform_any_class;
        public boolean can_generate_resource_exhaustion_heap_events;
        public boolean can_generate_resource_exhaustion_threads_events;

        @Override
        public String toString() {
            return "JVMTICapabilities{" +
                    "can_tag_objects=" + can_tag_objects +
                    ", can_generate_field_modification_events=" + can_generate_field_modification_events +
                    ", can_generate_field_access_events=" + can_generate_field_access_events +
                    ", can_get_bytecodes=" + can_get_bytecodes +
                    ", can_get_synthetic_attribute=" + can_get_synthetic_attribute +
                    ", can_get_owned_monitor_info=" + can_get_owned_monitor_info +
                    ", can_get_current_contended_monitor=" + can_get_current_contended_monitor +
                    ", can_get_monitor_info=" + can_get_monitor_info +
                    ", can_pop_frame=" + can_pop_frame +
                    ", can_redefine_classes=" + can_redefine_classes +
                    ", can_signal_thread=" + can_signal_thread +
                    ", can_get_source_file_name=" + can_get_source_file_name +
                    ", can_get_line_numbers=" + can_get_line_numbers +
                    ", can_get_source_debug_extension=" + can_get_source_debug_extension +
                    ", can_access_local_variables=" + can_access_local_variables +
                    ", can_maintain_original_method_order=" + can_maintain_original_method_order +
                    ", can_generate_single_step_events=" + can_generate_single_step_events +
                    ", can_generate_exception_events=" + can_generate_exception_events +
                    ", can_generate_frame_pop_events=" + can_generate_frame_pop_events +
                    ", can_generate_breakpoint_events=" + can_generate_breakpoint_events +
                    ", can_suspend=" + can_suspend +
                    ", can_redefine_any_class=" + can_redefine_any_class +
                    ", can_get_current_thread_cpu_time=" + can_get_current_thread_cpu_time +
                    ", can_get_thread_cpu_time=" + can_get_thread_cpu_time +
                    ", can_generate_method_entry_events=" + can_generate_method_entry_events +
                    ", can_generate_method_exit_events=" + can_generate_method_exit_events +
                    ", can_generate_all_class_hook_events=" + can_generate_all_class_hook_events +
                    ", can_generate_compiled_method_load_events=" + can_generate_compiled_method_load_events +
                    ", can_generate_monitor_events=" + can_generate_monitor_events +
                    ", can_generate_vm_object_alloc_events=" + can_generate_vm_object_alloc_events +
                    ", can_generate_native_method_bind_events=" + can_generate_native_method_bind_events +
                    ", can_generate_garbage_collection_events=" + can_generate_garbage_collection_events +
                    ", can_generate_object_free_events=" + can_generate_object_free_events +
                    ", can_force_early_return=" + can_force_early_return +
                    ", can_get_owned_monitor_stack_depth_info=" + can_get_owned_monitor_stack_depth_info +
                    ", can_get_constant_pool=" + can_get_constant_pool +
                    ", can_set_native_method_prefix=" + can_set_native_method_prefix +
                    ", can_retransform_classes=" + can_retransform_classes +
                    ", can_retransform_any_class=" + can_retransform_any_class +
                    ", can_generate_resource_exhaustion_heap_events=" + can_generate_resource_exhaustion_heap_events +
                    ", can_generate_resource_exhaustion_threads_events=" + can_generate_resource_exhaustion_threads_events +
                    '}';
        }

        private static boolean bit(long bits, int index) {
            return (bits & 1L << index) != 0;
        }

        private static long bit(long bits, int index, boolean value) {
            return value ? (bits | 1L << index) : (bits & ~(1L << index));
        }

        static void set(JVMTICapabilities caps, long value) {
            caps.can_tag_objects = bit(value, 0);
            caps.can_generate_field_modification_events = bit(value, 1);
            caps.can_generate_field_access_events = bit(value, 2);
            caps.can_get_bytecodes = bit(value, 3);
            caps.can_get_synthetic_attribute = bit(value, 4);
            caps.can_get_owned_monitor_info = bit(value, 5);
            caps.can_get_current_contended_monitor = bit(value, 6);
            caps.can_get_monitor_info = bit(value, 7);
            caps.can_pop_frame = bit(value, 8);
            caps.can_redefine_classes = bit(value, 9);
            caps.can_signal_thread = bit(value, 10);
            caps.can_get_source_file_name = bit(value, 11);
            caps.can_get_line_numbers = bit(value, 12);
            caps.can_get_source_debug_extension = bit(value, 13);
            caps.can_access_local_variables = bit(value, 14);
            caps.can_maintain_original_method_order = bit(value, 15);
            caps.can_generate_single_step_events = bit(value, 16);
            caps.can_generate_exception_events = bit(value, 17);
            caps.can_generate_frame_pop_events = bit(value, 18);
            caps.can_generate_breakpoint_events = bit(value, 19);
            caps.can_suspend = bit(value, 20);
            caps.can_redefine_any_class = bit(value, 21);
            caps.can_get_current_thread_cpu_time = bit(value, 22);
            caps.can_get_thread_cpu_time = bit(value, 23);
            caps.can_generate_method_entry_events = bit(value, 24);
            caps.can_generate_method_exit_events = bit(value, 25);
            caps.can_generate_all_class_hook_events = bit(value, 26);
            caps.can_generate_compiled_method_load_events = bit(value, 27);
            caps.can_generate_monitor_events = bit(value, 28);
            caps.can_generate_vm_object_alloc_events = bit(value, 29);
            caps.can_generate_native_method_bind_events = bit(value, 30);
            caps.can_generate_garbage_collection_events = bit(value, 31);
            caps.can_generate_object_free_events = bit(value, 32);
            caps.can_force_early_return = bit(value, 33);
            caps.can_get_owned_monitor_stack_depth_info = bit(value, 34);
            caps.can_get_constant_pool = bit(value, 35);
            caps.can_set_native_method_prefix = bit(value, 36);
            caps.can_retransform_classes = bit(value, 37);
            caps.can_retransform_any_class = bit(value, 38);
            caps.can_generate_resource_exhaustion_heap_events = bit(value, 39);
            caps.can_generate_resource_exhaustion_threads_events = bit(value, 40);
        }

        static long get(JVMTICapabilities caps) {
            long out = 0;
            out = bit(out, 0, caps.can_tag_objects);
            out = bit(out, 1, caps.can_generate_field_modification_events);
            out = bit(out, 2, caps.can_generate_field_access_events);
            out = bit(out, 3, caps.can_get_bytecodes);
            out = bit(out, 4, caps.can_get_synthetic_attribute);
            out = bit(out, 5, caps.can_get_owned_monitor_info);
            out = bit(out, 6, caps.can_get_current_contended_monitor);
            out = bit(out, 7, caps.can_get_monitor_info);
            out = bit(out, 8, caps.can_pop_frame);
            out = bit(out, 9, caps.can_redefine_classes);
            out = bit(out, 10, caps.can_signal_thread);
            out = bit(out, 11, caps.can_get_source_file_name);
            out = bit(out, 12, caps.can_get_line_numbers);
            out = bit(out, 13, caps.can_get_source_debug_extension);
            out = bit(out, 14, caps.can_access_local_variables);
            out = bit(out, 15, caps.can_maintain_original_method_order);
            out = bit(out, 16, caps.can_generate_single_step_events);
            out = bit(out, 17, caps.can_generate_exception_events);
            out = bit(out, 18, caps.can_generate_frame_pop_events);
            out = bit(out, 19, caps.can_generate_breakpoint_events);
            out = bit(out, 20, caps.can_suspend);
            out = bit(out, 21, caps.can_redefine_any_class);
            out = bit(out, 22, caps.can_get_current_thread_cpu_time);
            out = bit(out, 23, caps.can_get_thread_cpu_time);
            out = bit(out, 24, caps.can_generate_method_entry_events);
            out = bit(out, 25, caps.can_generate_method_exit_events);
            out = bit(out, 26, caps.can_generate_all_class_hook_events);
            out = bit(out, 27, caps.can_generate_compiled_method_load_events);
            out = bit(out, 28, caps.can_generate_monitor_events);
            out = bit(out, 29, caps.can_generate_vm_object_alloc_events);
            out = bit(out, 30, caps.can_generate_native_method_bind_events);
            out = bit(out, 31, caps.can_generate_garbage_collection_events);
            out = bit(out, 32, caps.can_generate_object_free_events);
            out = bit(out, 33, caps.can_force_early_return);
            out = bit(out, 34, caps.can_get_owned_monitor_stack_depth_info);
            out = bit(out, 35, caps.can_get_constant_pool);
            out = bit(out, 36, caps.can_set_native_method_prefix);
            out = bit(out, 37, caps.can_retransform_classes);
            out = bit(out, 38, caps.can_retransform_any_class);
            out = bit(out, 39, caps.can_generate_resource_exhaustion_heap_events);
            out = bit(out, 40, caps.can_generate_resource_exhaustion_threads_events);
            return out;
        }
    }

    private static void ForceAllPotentialCapabilities() throws JVMTIException {
        class Holder {
            private static final long ALL_CAPS = (~0L) >>> (64 - 41);
            private static final long GPC_OFFSET = JVMTI_INTERFACE_LAYOUT.
                    byteOffset(groupElement("GetPotentialCapabilities"));
            static final MemorySegment INTERFACE_COPY =
                    JVMTI_SCOPE.allocate(JVMTI_INTERFACE_LAYOUT);

            static {
                final String name = "function";
                MemorySegment getter = generateFunctionCodeSegment((context, module, builder) -> {
                    LLVMTypeRef f_type = function_t(int32_t(context), intptr_t(context), ptr_t(int128_t(context)));
                    LLVMValueRef function = LLVMAddFunction(module, name, f_type);
                    LLVMValueRef[] args = LLVMGetParams(function);

                    LLVMPositionBuilderAtEnd(builder, LLVMAppendBasicBlock(function, ""));
                    //TODO: check caps and return JVMTI_ERROR_NULL_POINTER if null
                    LLVMValueRef store = LLVMBuildStore(builder,
                            const_int128(context, ALL_CAPS, 0), args[1]);
                    LLVMSetAlignment(store, 1);

                    LLVMBuildRet(builder, const_int32(context, JVMTI_ERROR_NONE));
                }, name, JVMTI_SCOPE);
                INTERFACE_COPY.copyFrom(getJVMTIInterface());
                INTERFACE_COPY.set(ADDRESS, GPC_OFFSET, getter);
            }
        }
        putWordN(JVMTI_ENV, Holder.INTERFACE_COPY.nativeAddress());
        JavaForeignAccess.addOrCleanupIfFail(JVMTI_SCOPE.scope(),
                () -> putWordN(JVMTI_ENV, getJVMTIInterface().nativeAddress()));
    }

    @DoNotShrinkType
    @DoNotOptimize
    @SuppressWarnings("SameParameterValue")
    private abstract static class Native {
        @LibrarySymbol(name = "RedefineClasses")
        @CallSignature(type = NATIVE_STATIC_OMIT_ENV, ret = INT, args = {LONG_AS_WORD, INT, LONG_AS_WORD})
        abstract int RedefineClasses(long env, int class_count, long class_definitions);

        @LibrarySymbol(name = "GetObjectSize")
        @CallSignature(type = NATIVE_STATIC_OMIT_ENV, ret = INT, args = {LONG_AS_WORD, OBJECT, LONG_AS_WORD})
        abstract int GetObjectSize(long env, Object object, long size_ptr);

        @LibrarySymbol(name = "GetPotentialCapabilities")
        @CallSignature(type = NATIVE_STATIC_OMIT_ENV, ret = INT, args = {LONG_AS_WORD, LONG_AS_WORD})
        abstract int GetPotentialCapabilities(long env, long capabilities_ptr);

        @LibrarySymbol(name = "AddCapabilities")
        @CallSignature(type = NATIVE_STATIC_OMIT_ENV, ret = INT, args = {LONG_AS_WORD, LONG_AS_WORD})
        abstract int AddCapabilities(long env, long capabilities_ptr);

        @LibrarySymbol(name = "RelinquishCapabilities")
        @CallSignature(type = NATIVE_STATIC_OMIT_ENV, ret = INT, args = {LONG_AS_WORD, LONG_AS_WORD})
        abstract int RelinquishCapabilities(long env, long capabilities_ptr);

        @LibrarySymbol(name = "SetEventCallbacks")
        @CallSignature(type = NATIVE_STATIC_OMIT_ENV, ret = INT, args = {LONG_AS_WORD, LONG_AS_WORD, INT})
        abstract int SetEventCallbacks(long env, long callbacks, int size_of_callbacks);

        @LibrarySymbol(name = "SetEventNotificationMode")
        @CallSignature(type = NATIVE_STATIC_OMIT_ENV, ret = INT, args = {LONG_AS_WORD, INT, INT, OBJECT})
        abstract int SetEventNotificationMode(long env, int mode, int event_type, Object event_thread);

        @LibrarySymbol(name = "SetBreakpoint")
        @CallSignature(type = NATIVE_STATIC_OMIT_ENV, ret = INT, args = {LONG_AS_WORD, LONG_AS_WORD, LONG})
        abstract int SetBreakpoint(long env, long mid, long location);

        @LibrarySymbol(name = "ClearBreakpoint")
        @CallSignature(type = NATIVE_STATIC_OMIT_ENV, ret = INT, args = {LONG_AS_WORD, LONG_AS_WORD, LONG})
        abstract int ClearBreakpoint(long env, long mid, long location);

        @LibrarySymbol(name = "SuspendThread")
        @CallSignature(type = NATIVE_STATIC_OMIT_ENV, ret = INT, args = {LONG_AS_WORD, OBJECT})
        abstract int SuspendThread(long env, Object thread);

        @LibrarySymbol(name = "ResumeThread")
        @CallSignature(type = NATIVE_STATIC_OMIT_ENV, ret = INT, args = {LONG_AS_WORD, OBJECT})
        abstract int ResumeThread(long env, Object thread);

        @LibrarySymbol(name = "StopThread")
        @CallSignature(type = NATIVE_STATIC_OMIT_ENV, ret = INT, args = {LONG_AS_WORD, OBJECT})
        abstract int StopThread(long env, Object thread);

        @LibrarySymbol(name = "InterruptThread")
        @CallSignature(type = NATIVE_STATIC_OMIT_ENV, ret = INT, args = {LONG_AS_WORD, OBJECT})
        abstract int InterruptThread(long env, Object thread);

        @LibrarySymbol(name = "ForceEarlyReturnObject")
        @CallSignature(type = NATIVE_STATIC_OMIT_ENV, ret = INT, args = {LONG_AS_WORD, OBJECT, OBJECT})
        abstract int ForceEarlyReturnObject(long env, Object thread, Object value);

        @LibrarySymbol(name = "ForceEarlyReturnInt")
        @CallSignature(type = NATIVE_STATIC_OMIT_ENV, ret = INT, args = {LONG_AS_WORD, OBJECT, INT})
        abstract int ForceEarlyReturnInt(long env, Object thread, int value);

        @LibrarySymbol(name = "ForceEarlyReturnLong")
        @CallSignature(type = NATIVE_STATIC_OMIT_ENV, ret = INT, args = {LONG_AS_WORD, OBJECT, LONG})
        abstract int ForceEarlyReturnLong(long env, Object thread, long value);

        @LibrarySymbol(name = "ForceEarlyReturnFloat")
        @CallSignature(type = NATIVE_STATIC_OMIT_ENV, ret = INT, args = {LONG_AS_WORD, OBJECT, FLOAT})
        abstract int ForceEarlyReturnFloat(long env, Object thread, float value);

        @LibrarySymbol(name = "ForceEarlyReturnDouble")
        @CallSignature(type = NATIVE_STATIC_OMIT_ENV, ret = INT, args = {LONG_AS_WORD, OBJECT, DOUBLE})
        abstract int ForceEarlyReturnDouble(long env, Object thread, double value);

        @LibrarySymbol(name = "ForceEarlyReturnVoid")
        @CallSignature(type = NATIVE_STATIC_OMIT_ENV, ret = INT, args = {LONG_AS_WORD, OBJECT})
        abstract int ForceEarlyReturnVoid(long env, Object thread);

        @LibrarySymbol(name = "PopFrame")
        @CallSignature(type = NATIVE_STATIC_OMIT_ENV, ret = INT, args = {LONG_AS_WORD, OBJECT})
        abstract int PopFrame(long env, Object thread);

        @LibrarySymbol(name = "GetFrameCount")
        @CallSignature(type = NATIVE_STATIC_OMIT_ENV, ret = INT, args = {LONG_AS_WORD, OBJECT, LONG_AS_WORD})
        abstract int GetFrameCount(long env, Object thread, long count_ptr);

        @LibrarySymbol(name = "GetLocalInstance")
        @CallSignature(type = CRITICAL, ret = INT, args = {LONG_AS_WORD, OBJECT, INT, LONG_AS_WORD})
        abstract int GetLocalInstance(long env, Object thread, int depth, long value_ptr);

        @LibrarySymbol(name = "GetLocalObject")
        @CallSignature(type = CRITICAL, ret = INT, args = {LONG_AS_WORD, OBJECT, INT, INT, LONG_AS_WORD})
        abstract int GetLocalObject(long env, Object thread, int depth, int slot, long value_ptr);

        @LibrarySymbol(name = "GetLocalInt")
        @CallSignature(type = CRITICAL, ret = INT, args = {LONG_AS_WORD, OBJECT, INT, INT, LONG_AS_WORD})
        abstract int GetLocalInt(long env, Object thread, int depth, int slot, long value_ptr);

        @LibrarySymbol(name = "GetLocalLong")
        @CallSignature(type = CRITICAL, ret = INT, args = {LONG_AS_WORD, OBJECT, INT, INT, LONG_AS_WORD})
        abstract int GetLocalLong(long env, Object thread, int depth, int slot, long value_ptr);

        @LibrarySymbol(name = "GetLocalFloat")
        @CallSignature(type = CRITICAL, ret = INT, args = {LONG_AS_WORD, OBJECT, INT, INT, LONG_AS_WORD})
        abstract int GetLocalFloat(long env, Object thread, int depth, int slot, long value_ptr);

        @LibrarySymbol(name = "GetLocalDouble")
        @CallSignature(type = CRITICAL, ret = INT, args = {LONG_AS_WORD, OBJECT, INT, INT, LONG_AS_WORD})
        abstract int GetLocalDouble(long env, Object thread, int depth, int slot, long value_ptr);

        @LibrarySymbol(name = "SetLocalObject")
        @CallSignature(type = CRITICAL, ret = INT, args = {LONG_AS_WORD, OBJECT, INT, INT, OBJECT})
        abstract int SetLocalObject(long env, Object thread, int depth, int slot, Object value);

        @LibrarySymbol(name = "SetLocalInt")
        @CallSignature(type = CRITICAL, ret = INT, args = {LONG_AS_WORD, OBJECT, INT, INT, INT})
        abstract int SetLocalInt(long env, Object thread, int depth, int slot, int value);

        @LibrarySymbol(name = "SetLocalLong")
        @CallSignature(type = CRITICAL, ret = INT, args = {LONG_AS_WORD, OBJECT, INT, INT, LONG})
        abstract int SetLocalLong(long env, Object thread, int depth, int slot, long value);

        @LibrarySymbol(name = "SetLocalFloat")
        @CallSignature(type = CRITICAL, ret = INT, args = {LONG_AS_WORD, OBJECT, INT, INT, FLOAT})
        abstract int SetLocalFloat(long env, Object thread, int depth, int slot, float value);

        @LibrarySymbol(name = "SetLocalDouble")
        @CallSignature(type = CRITICAL, ret = INT, args = {LONG_AS_WORD, OBJECT, INT, INT, DOUBLE})
        abstract int SetLocalDouble(long env, Object thread, int depth, int slot, double value);

        static final Native INSTANCE = AndroidUnsafe.allocateInstance(
                BulkLinker.processSymbols(JVMTI_SCOPE, Native.class, getJVMTIInterfaceLookup()));
    }

    private static void checkError(int error) {
        if (error == JVMTI_ERROR_NONE) {
            return;
        }
        throw new JVMTIException(error);
    }

    public static String GetErrorName(int error) {
        return switch (error) {
            case JVMTI_ERROR_NONE -> "NONE";
            case JVMTI_ERROR_INVALID_THREAD -> "INVALID_THREAD";
            case JVMTI_ERROR_INVALID_THREAD_GROUP -> "INVALID_THREAD_GROUP";
            case JVMTI_ERROR_INVALID_PRIORITY -> "INVALID_PRIORITY";
            case JVMTI_ERROR_THREAD_NOT_SUSPENDED -> "THREAD_NOT_SUSPENDED";
            case JVMTI_ERROR_THREAD_SUSPENDED -> "THREAD_SUSPENDED";
            case JVMTI_ERROR_THREAD_NOT_ALIVE -> "THREAD_NOT_ALIVE";
            case JVMTI_ERROR_INVALID_OBJECT -> "INVALID_OBJECT";
            case JVMTI_ERROR_INVALID_CLASS -> "INVALID_CLASS";
            case JVMTI_ERROR_CLASS_NOT_PREPARED -> "CLASS_NOT_PREPARED";
            case JVMTI_ERROR_INVALID_METHODID -> "INVALID_METHODID";
            case JVMTI_ERROR_INVALID_LOCATION -> "INVALID_LOCATION";
            case JVMTI_ERROR_INVALID_FIELDID -> "INVALID_FIELDID";
            case JVMTI_ERROR_NO_MORE_FRAMES -> "NO_MORE_FRAMES";
            case JVMTI_ERROR_OPAQUE_FRAME -> "OPAQUE_FRAME";
            case JVMTI_ERROR_TYPE_MISMATCH -> "TYPE_MISMATCH";
            case JVMTI_ERROR_INVALID_SLOT -> "INVALID_SLOT";
            case JVMTI_ERROR_DUPLICATE -> "DUPLICATE";
            case JVMTI_ERROR_NOT_FOUND -> "NOT_FOUND";
            case JVMTI_ERROR_INVALID_MONITOR -> "INVALID_MONITOR";
            case JVMTI_ERROR_NOT_MONITOR_OWNER -> "NOT_MONITOR_OWNER";
            case JVMTI_ERROR_INTERRUPT -> "INTERRUPT";
            case JVMTI_ERROR_INVALID_CLASS_FORMAT -> "INVALID_CLASS_FORMAT";
            case JVMTI_ERROR_CIRCULAR_CLASS_DEFINITION -> "CIRCULAR_CLASS_DEFINITION";
            case JVMTI_ERROR_FAILS_VERIFICATION -> "FAILS_VERIFICATION";
            case JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_ADDED ->
                    "UNSUPPORTED_REDEFINITION_METHOD_ADDED";
            case JVMTI_ERROR_UNSUPPORTED_REDEFINITION_SCHEMA_CHANGED ->
                    "UNSUPPORTED_REDEFINITION_SCHEMA_CHANGED";
            case JVMTI_ERROR_INVALID_TYPESTATE -> "INVALID_TYPESTATE";
            case JVMTI_ERROR_UNSUPPORTED_REDEFINITION_HIERARCHY_CHANGED ->
                    "UNSUPPORTED_REDEFINITION_HIERARCHY_CHANGED";
            case JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_DELETED ->
                    "UNSUPPORTED_REDEFINITION_METHOD_DELETED";
            case JVMTI_ERROR_UNSUPPORTED_VERSION -> "UNSUPPORTED_VERSION";
            case JVMTI_ERROR_NAMES_DONT_MATCH -> "NAMES_DONT_MATCH";
            case JVMTI_ERROR_UNSUPPORTED_REDEFINITION_CLASS_MODIFIERS_CHANGED ->
                    "UNSUPPORTED_REDEFINITION_CLASS_MODIFIERS_CHANGED";
            case JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_MODIFIERS_CHANGED ->
                    "UNSUPPORTED_REDEFINITION_METHOD_MODIFIERS_CHANGED";
            case JVMTI_ERROR_UNMODIFIABLE_CLASS -> "UNMODIFIABLE_CLASS";
            case JVMTI_ERROR_NOT_AVAILABLE -> "NOT_AVAILABLE";
            case JVMTI_ERROR_MUST_POSSESS_CAPABILITY -> "MUST_POSSESS_CAPABILITY";
            case JVMTI_ERROR_NULL_POINTER -> "NULL_POINTER";
            case JVMTI_ERROR_ABSENT_INFORMATION -> "ABSENT_INFORMATION";
            case JVMTI_ERROR_INVALID_EVENT_TYPE -> "INVALID_EVENT_TYPE";
            case JVMTI_ERROR_ILLEGAL_ARGUMENT -> "ILLEGAL_ARGUMENT";
            case JVMTI_ERROR_NATIVE_METHOD -> "NATIVE_METHOD";
            case JVMTI_ERROR_CLASS_LOADER_UNSUPPORTED -> "CLASS_LOADER_UNSUPPORTED";
            case JVMTI_ERROR_OUT_OF_MEMORY -> "OUT_OF_MEMORY";
            case JVMTI_ERROR_ACCESS_DENIED -> "ACCESS_DENIED";
            case JVMTI_ERROR_WRONG_PHASE -> "WRONG_PHASE";
            case JVMTI_ERROR_INTERNAL -> "INTERNAL";
            case JVMTI_ERROR_UNATTACHED_THREAD -> "UNATTACHED_THREAD";
            case JVMTI_ERROR_INVALID_ENVIRONMENT -> "INVALID_ENVIRONMENT";
            default -> "Unknown JVMTI_ERROR code: " + error;
        };
    }

    @SafeVarargs
    public static void RedefineClasses(Pair<Class<?>, byte[]>... class_definitions) throws JVMTIException {
        Objects.requireNonNull(class_definitions);
        int entries = class_definitions.length;
        if (entries == 0) {
            return;
        }
        int bytes = 0;
        for (var pair : class_definitions) {
            Objects.requireNonNull(pair);
            Objects.requireNonNull(pair.first);
            Objects.requireNonNull(pair.second);
            bytes = Math.addExact(bytes, pair.second.length);
        }
        class Holder {
            static final MemoryLayout LAYOUT = paddedStructLayout(
                    WORD.withName("klass"),
                    JAVA_INT.withName("class_byte_count"),
                    ADDRESS.withName("class_bytes")
            );
            static final long DEF_SIZE = LAYOUT.byteSize();
            static final long KLASS_OFFSET = LAYOUT.byteOffset(groupElement("klass"));
            static final long COUNT_OFFSET = LAYOUT.byteOffset(groupElement("class_byte_count"));
            static final long BYTES_OFFSET = LAYOUT.byteOffset(groupElement("class_bytes"));
        }
        JNIUtils.PushLocalFrame(entries);
        try (Arena scope = Arena.ofConfined()) {
            int offset = 0;
            MemorySegment data = scope.allocate(bytes);
            long data_address = data.nativeAddress();
            MemorySegment defs = scope.allocate(Holder.LAYOUT, entries);
            long defs_address = defs.nativeAddress();
            for (int i = 0; i < entries; i++) {
                var def = class_definitions[i];
                int length = def.second.length;
                MemorySegment.copy(def.second, 0, data, JAVA_BYTE, offset, length);
                long klass = JNIUtils.NewLocalRef(def.first);
                long def_offset = Holder.DEF_SIZE * i;
                putWordN(defs_address + def_offset + Holder.KLASS_OFFSET, klass);
                putIntN(defs_address + def_offset + Holder.COUNT_OFFSET, length);
                putWordN(defs_address + def_offset + Holder.BYTES_OFFSET, data_address + offset);
                offset += length;
            }
            checkError(Native.INSTANCE.RedefineClasses(JVMTI_ENV, entries, defs_address));
        } finally {
            JNIUtils.PopLocalFrame();
        }
    }

    public static void RedefineClass(Class<?> klass, byte[] data) throws JVMTIException {
        RedefineClasses(new Pair<>(klass, data));
    }

    public static long GetObjectSize(Object obj) throws JVMTIException {
        try (Arena scope = Arena.ofConfined()) {
            MemorySegment size_ptr = scope.allocate(JAVA_LONG);
            checkError(Native.INSTANCE.GetObjectSize(JVMTI_ENV, obj, size_ptr.nativeAddress()));
            return size_ptr.get(JAVA_LONG, 0);
        }
    }

    public static void GetPotentialCapabilities(JVMTICapabilities capabilities) throws JVMTIException {
        Objects.requireNonNull(capabilities);
        try (Arena scope = Arena.ofConfined()) {
            MemorySegment ptr = scope.allocate(JVMTICapabilities.LAYOUT);
            checkError(Native.INSTANCE.GetPotentialCapabilities(JVMTI_ENV, ptr.nativeAddress()));
            JVMTICapabilities.set(capabilities, ptr.get(JAVA_LONG_UNALIGNED, 0));
        }
    }

    public static void AddCapabilities(JVMTICapabilities capabilities) throws JVMTIException {
        Objects.requireNonNull(capabilities);
        try (Arena scope = Arena.ofConfined()) {
            MemorySegment ptr = scope.allocate(JVMTICapabilities.LAYOUT);
            ptr.set(JAVA_LONG_UNALIGNED, 0, JVMTICapabilities.get(capabilities));
            checkError(Native.INSTANCE.AddCapabilities(JVMTI_ENV, ptr.nativeAddress()));
        }
    }

    public static void RelinquishCapabilities(JVMTICapabilities capabilities) throws JVMTIException {
        Objects.requireNonNull(capabilities);
        try (Arena scope = Arena.ofConfined()) {
            MemorySegment ptr = scope.allocate(JVMTICapabilities.LAYOUT);
            ptr.set(JAVA_LONG_UNALIGNED, 0, JVMTICapabilities.get(capabilities));
            checkError(Native.INSTANCE.RelinquishCapabilities(JVMTI_ENV, ptr.nativeAddress()));
        }
    }

    // Used in JVMTIEvents
    static void SetEventCallbacks(long callbacks, int size_of_callbacks) throws JVMTIException {
        checkError(Native.INSTANCE.SetEventCallbacks(JVMTI_ENV, callbacks, size_of_callbacks));
    }

    // Used in JVMTIEvents
    static void SetEventNotificationMode(int mode, int event_type, Thread event_thread) throws JVMTIException {
        checkError(Native.INSTANCE.SetEventNotificationMode(JVMTI_ENV, mode, event_type, event_thread));
    }

    public static void SetBreakpoint(Method method, long location) throws JVMTIException {
        checkError(Native.INSTANCE.SetBreakpoint(JVMTI_ENV, JNIUtils.FromReflectedMethod(method), location));
    }

    public static void ClearBreakpoint(Method method, long location) throws JVMTIException {
        checkError(Native.INSTANCE.ClearBreakpoint(JVMTI_ENV, JNIUtils.FromReflectedMethod(method), location));
    }

    public static void SuspendThread(Thread thread) throws JVMTIException {
        checkError(Native.INSTANCE.SuspendThread(JVMTI_ENV, thread));
    }

    public static void ResumeThread(Thread thread) throws JVMTIException {
        checkError(Native.INSTANCE.ResumeThread(JVMTI_ENV, thread));
    }

    public static void StopThread(Thread thread) throws JVMTIException {
        checkError(Native.INSTANCE.StopThread(JVMTI_ENV, thread));
    }

    public static void InterruptThread(Thread thread) throws JVMTIException {
        checkError(Native.INSTANCE.InterruptThread(JVMTI_ENV, thread));
    }

    @AlwaysInline
    private static boolean isCurrent(Thread thread) {
        return thread == Thread.currentThread();
    }

    private static RuntimeException UCT(String op_name) {
        // TODO: what if we need to force return or pop frame for current thread?
        throw new UnsupportedOperationException(op_name + " is unsupported for current thread");
    }

    public static void ForceEarlyReturnObject(Thread thread, Object value) {
        if (isCurrent(thread)) throw UCT("ForceEarlyReturnObject");
        checkError(Native.INSTANCE.ForceEarlyReturnObject(JVMTI_ENV, thread, value));
    }

    public static void ForceEarlyReturnInt(Thread thread, int value) {
        if (isCurrent(thread)) throw UCT("ForceEarlyReturnInt");
        checkError(Native.INSTANCE.ForceEarlyReturnInt(JVMTI_ENV, thread, value));
    }

    public static void ForceEarlyReturnLong(Thread thread, long value) {
        if (isCurrent(thread)) throw UCT("ForceEarlyReturnLong");
        checkError(Native.INSTANCE.ForceEarlyReturnLong(JVMTI_ENV, thread, value));
    }

    public static void ForceEarlyReturnFloat(Thread thread, float value) {
        if (isCurrent(thread)) throw UCT("ForceEarlyReturnFloat");
        checkError(Native.INSTANCE.ForceEarlyReturnFloat(JVMTI_ENV, thread, value));
    }

    public static void ForceEarlyReturnDouble(Thread thread, double value) {
        if (isCurrent(thread)) throw UCT("ForceEarlyReturnDouble");
        checkError(Native.INSTANCE.ForceEarlyReturnDouble(JVMTI_ENV, thread, value));
    }

    public static void ForceEarlyReturnVoid(Thread thread) {
        if (isCurrent(thread)) throw UCT("ForceEarlyReturnVoid");
        checkError(Native.INSTANCE.ForceEarlyReturnVoid(JVMTI_ENV, thread));
    }

    public static void PopFrame(Thread thread) {
        if (isCurrent(thread)) throw UCT("PopFrame");
        checkError(Native.INSTANCE.PopFrame(JVMTI_ENV, thread));
    }

    private static final int STUB_DEPTH = 3;

    public static int GetFrameCount(Thread thread) {
        int out;
        try (Arena scope = Arena.ofConfined()) {
            MemorySegment count_ptr = scope.allocate(JAVA_INT);
            checkError(Native.INSTANCE.GetFrameCount(
                    JVMTI_ENV, thread, count_ptr.nativeAddress()));
            out = count_ptr.get(JAVA_INT, 0);
        }
        return isCurrent(thread) ? out - STUB_DEPTH : out;
    }

    public static Object GetLocalInstance(Thread thread, int depth) {
        depth = isCurrent(thread) ? depth + STUB_DEPTH : depth;
        try (Arena scope = Arena.ofConfined()) {
            // Note: value is word, but long is allocated, which may be bigger than necessary
            MemorySegment value_ptr = scope.allocate(JAVA_LONG);
            checkError(Native.INSTANCE.GetLocalInstance(
                    JVMTI_ENV, thread, depth, value_ptr.nativeAddress()));
            long ref = value_ptr.get(JAVA_LONG, 0);
            Object out = JNIUtils.refToObject(ref);
            JNIUtils.DeleteLocalRef(ref);
            return out;
        }
    }

    public static Object GetLocalObject(Thread thread, int depth, int slot) {
        depth = isCurrent(thread) ? depth + STUB_DEPTH : depth;
        try (Arena scope = Arena.ofConfined()) {
            // Note: value is word, but long is allocated, which may be bigger than necessary
            MemorySegment value_ptr = scope.allocate(JAVA_LONG);
            checkError(Native.INSTANCE.GetLocalObject(
                    JVMTI_ENV, thread, depth, slot, value_ptr.nativeAddress()));
            long ref = value_ptr.get(JAVA_LONG, 0);
            Object out = JNIUtils.refToObject(ref);
            JNIUtils.DeleteLocalRef(ref);
            return out;
        }
    }

    public static int GetLocalInt(Thread thread, int depth, int slot) {
        depth = isCurrent(thread) ? depth + STUB_DEPTH : depth;
        try (Arena scope = Arena.ofConfined()) {
            MemorySegment value_ptr = scope.allocate(JAVA_INT);
            checkError(Native.INSTANCE.GetLocalInt(
                    JVMTI_ENV, thread, depth, slot, value_ptr.nativeAddress()));
            return value_ptr.get(JAVA_INT, 0);
        }
    }

    public static long GetLocalLong(Thread thread, int depth, int slot) {
        depth = isCurrent(thread) ? depth + STUB_DEPTH : depth;
        try (Arena scope = Arena.ofConfined()) {
            MemorySegment value_ptr = scope.allocate(JAVA_LONG);
            checkError(Native.INSTANCE.GetLocalLong(
                    JVMTI_ENV, thread, depth, slot, value_ptr.nativeAddress()));
            return value_ptr.get(JAVA_LONG, 0);
        }
    }

    public static float GetLocalFloat(Thread thread, int depth, int slot) {
        depth = isCurrent(thread) ? depth + STUB_DEPTH : depth;
        try (Arena scope = Arena.ofConfined()) {
            MemorySegment value_ptr = scope.allocate(JAVA_FLOAT);
            checkError(Native.INSTANCE.GetLocalFloat(
                    JVMTI_ENV, thread, depth, slot, value_ptr.nativeAddress()));
            return value_ptr.get(JAVA_FLOAT, 0);
        }
    }

    public static double GetLocalDouble(Thread thread, int depth, int slot) {
        depth = isCurrent(thread) ? depth + STUB_DEPTH : depth;
        try (Arena scope = Arena.ofConfined()) {
            MemorySegment value_ptr = scope.allocate(JAVA_DOUBLE);
            checkError(Native.INSTANCE.GetLocalDouble(
                    JVMTI_ENV, thread, depth, slot, value_ptr.nativeAddress()));
            return value_ptr.get(JAVA_DOUBLE, 0);
        }
    }

    public static void SetLocalObject(Thread thread, int depth, int slot, Object value) {
        depth = isCurrent(thread) ? depth + STUB_DEPTH : depth;
        checkError(Native.INSTANCE.SetLocalObject(JVMTI_ENV, thread, depth, slot, value));
    }

    public static void SetLocalInt(Thread thread, int depth, int slot, int value) {
        depth = isCurrent(thread) ? depth + STUB_DEPTH : depth;
        checkError(Native.INSTANCE.SetLocalInt(JVMTI_ENV, thread, depth, slot, value));
    }

    public static void SetLocalLong(Thread thread, int depth, int slot, long value) {
        depth = isCurrent(thread) ? depth + STUB_DEPTH : depth;
        checkError(Native.INSTANCE.SetLocalLong(JVMTI_ENV, thread, depth, slot, value));
    }

    public static void SetLocalFloat(Thread thread, int depth, int slot, float value) {
        depth = isCurrent(thread) ? depth + STUB_DEPTH : depth;
        checkError(Native.INSTANCE.SetLocalFloat(JVMTI_ENV, thread, depth, slot, value));
    }

    public static void SetLocalDouble(Thread thread, int depth, int slot, double value) {
        depth = isCurrent(thread) ? depth + STUB_DEPTH : depth;
        checkError(Native.INSTANCE.SetLocalDouble(JVMTI_ENV, thread, depth, slot, value));
    }
}

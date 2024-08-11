package com.v7878.vmtools;

import static com.v7878.foreign.MemoryLayout.PathElement.groupElement;
import static com.v7878.foreign.MemoryLayout.paddedStructLayout;
import static com.v7878.foreign.MemoryLayout.structLayout;
import static com.v7878.foreign.ValueLayout.ADDRESS;
import static com.v7878.foreign.ValueLayout.JAVA_BYTE;
import static com.v7878.foreign.ValueLayout.JAVA_INT;
import static com.v7878.foreign.ValueLayout.JAVA_LONG;
import static com.v7878.foreign.ValueLayout.JAVA_LONG_UNALIGNED;
import static com.v7878.llvm.Core.LLVMAddFunction;
import static com.v7878.llvm.Core.LLVMAppendBasicBlock;
import static com.v7878.llvm.Core.LLVMBuildRet;
import static com.v7878.llvm.Core.LLVMBuildStore;
import static com.v7878.llvm.Core.LLVMFunctionType;
import static com.v7878.llvm.Core.LLVMGetParams;
import static com.v7878.llvm.Core.LLVMPositionBuilderAtEnd;
import static com.v7878.llvm.Core.LLVMSetAlignment;
import static com.v7878.misc.Version.CORRECT_SDK_INT;
import static com.v7878.unsafe.AndroidUnsafe.putIntN;
import static com.v7878.unsafe.AndroidUnsafe.putWordN;
import static com.v7878.unsafe.JNIUtils.JNI_EVERSION;
import static com.v7878.unsafe.JNIUtils.JNI_OK;
import static com.v7878.unsafe.JNIUtils.getJNIInvokeInterfaceLookup;
import static com.v7878.unsafe.JNIUtils.getJavaVMPtr;
import static com.v7878.unsafe.foreign.BulkLinker.CallType.CRITICAL;
import static com.v7878.unsafe.foreign.BulkLinker.CallType.NATIVE_STATIC_OMIT_ENV;
import static com.v7878.unsafe.foreign.BulkLinker.MapType.BOOL;
import static com.v7878.unsafe.foreign.BulkLinker.MapType.INT;
import static com.v7878.unsafe.foreign.BulkLinker.MapType.LONG;
import static com.v7878.unsafe.foreign.BulkLinker.MapType.LONG_AS_WORD;
import static com.v7878.unsafe.foreign.BulkLinker.MapType.OBJECT;
import static com.v7878.unsafe.foreign.ExtraLayouts.WORD;
import static com.v7878.unsafe.llvm.LLVMGlobals.int128_t;
import static com.v7878.unsafe.llvm.LLVMGlobals.int32_t;
import static com.v7878.unsafe.llvm.LLVMGlobals.intptr_t;
import static com.v7878.unsafe.llvm.LLVMGlobals.ptr_t;
import static com.v7878.unsafe.llvm.LLVMUtils.const_int128;
import static com.v7878.unsafe.llvm.LLVMUtils.const_int32;
import static com.v7878.unsafe.llvm.LLVMUtils.generateFunctionCodeSegment;
import static com.v7878.vmtools.JVMTIConstants.JVMTI_ERROR_ABSENT_INFORMATION;
import static com.v7878.vmtools.JVMTIConstants.JVMTI_ERROR_ACCESS_DENIED;
import static com.v7878.vmtools.JVMTIConstants.JVMTI_ERROR_CIRCULAR_CLASS_DEFINITION;
import static com.v7878.vmtools.JVMTIConstants.JVMTI_ERROR_CLASS_LOADER_UNSUPPORTED;
import static com.v7878.vmtools.JVMTIConstants.JVMTI_ERROR_CLASS_NOT_PREPARED;
import static com.v7878.vmtools.JVMTIConstants.JVMTI_ERROR_DUPLICATE;
import static com.v7878.vmtools.JVMTIConstants.JVMTI_ERROR_FAILS_VERIFICATION;
import static com.v7878.vmtools.JVMTIConstants.JVMTI_ERROR_ILLEGAL_ARGUMENT;
import static com.v7878.vmtools.JVMTIConstants.JVMTI_ERROR_INTERNAL;
import static com.v7878.vmtools.JVMTIConstants.JVMTI_ERROR_INTERRUPT;
import static com.v7878.vmtools.JVMTIConstants.JVMTI_ERROR_INVALID_CLASS;
import static com.v7878.vmtools.JVMTIConstants.JVMTI_ERROR_INVALID_CLASS_FORMAT;
import static com.v7878.vmtools.JVMTIConstants.JVMTI_ERROR_INVALID_ENVIRONMENT;
import static com.v7878.vmtools.JVMTIConstants.JVMTI_ERROR_INVALID_EVENT_TYPE;
import static com.v7878.vmtools.JVMTIConstants.JVMTI_ERROR_INVALID_FIELDID;
import static com.v7878.vmtools.JVMTIConstants.JVMTI_ERROR_INVALID_LOCATION;
import static com.v7878.vmtools.JVMTIConstants.JVMTI_ERROR_INVALID_METHODID;
import static com.v7878.vmtools.JVMTIConstants.JVMTI_ERROR_INVALID_MONITOR;
import static com.v7878.vmtools.JVMTIConstants.JVMTI_ERROR_INVALID_OBJECT;
import static com.v7878.vmtools.JVMTIConstants.JVMTI_ERROR_INVALID_PRIORITY;
import static com.v7878.vmtools.JVMTIConstants.JVMTI_ERROR_INVALID_SLOT;
import static com.v7878.vmtools.JVMTIConstants.JVMTI_ERROR_INVALID_THREAD;
import static com.v7878.vmtools.JVMTIConstants.JVMTI_ERROR_INVALID_THREAD_GROUP;
import static com.v7878.vmtools.JVMTIConstants.JVMTI_ERROR_INVALID_TYPESTATE;
import static com.v7878.vmtools.JVMTIConstants.JVMTI_ERROR_MUST_POSSESS_CAPABILITY;
import static com.v7878.vmtools.JVMTIConstants.JVMTI_ERROR_NAMES_DONT_MATCH;
import static com.v7878.vmtools.JVMTIConstants.JVMTI_ERROR_NATIVE_METHOD;
import static com.v7878.vmtools.JVMTIConstants.JVMTI_ERROR_NONE;
import static com.v7878.vmtools.JVMTIConstants.JVMTI_ERROR_NOT_AVAILABLE;
import static com.v7878.vmtools.JVMTIConstants.JVMTI_ERROR_NOT_FOUND;
import static com.v7878.vmtools.JVMTIConstants.JVMTI_ERROR_NOT_MONITOR_OWNER;
import static com.v7878.vmtools.JVMTIConstants.JVMTI_ERROR_NO_MORE_FRAMES;
import static com.v7878.vmtools.JVMTIConstants.JVMTI_ERROR_NULL_POINTER;
import static com.v7878.vmtools.JVMTIConstants.JVMTI_ERROR_OPAQUE_FRAME;
import static com.v7878.vmtools.JVMTIConstants.JVMTI_ERROR_OUT_OF_MEMORY;
import static com.v7878.vmtools.JVMTIConstants.JVMTI_ERROR_THREAD_NOT_ALIVE;
import static com.v7878.vmtools.JVMTIConstants.JVMTI_ERROR_THREAD_NOT_SUSPENDED;
import static com.v7878.vmtools.JVMTIConstants.JVMTI_ERROR_THREAD_SUSPENDED;
import static com.v7878.vmtools.JVMTIConstants.JVMTI_ERROR_TYPE_MISMATCH;
import static com.v7878.vmtools.JVMTIConstants.JVMTI_ERROR_UNATTACHED_THREAD;
import static com.v7878.vmtools.JVMTIConstants.JVMTI_ERROR_UNMODIFIABLE_CLASS;
import static com.v7878.vmtools.JVMTIConstants.JVMTI_ERROR_UNSUPPORTED_REDEFINITION_CLASS_MODIFIERS_CHANGED;
import static com.v7878.vmtools.JVMTIConstants.JVMTI_ERROR_UNSUPPORTED_REDEFINITION_HIERARCHY_CHANGED;
import static com.v7878.vmtools.JVMTIConstants.JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_ADDED;
import static com.v7878.vmtools.JVMTIConstants.JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_DELETED;
import static com.v7878.vmtools.JVMTIConstants.JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_MODIFIERS_CHANGED;
import static com.v7878.vmtools.JVMTIConstants.JVMTI_ERROR_UNSUPPORTED_REDEFINITION_SCHEMA_CHANGED;
import static com.v7878.vmtools.JVMTIConstants.JVMTI_ERROR_UNSUPPORTED_VERSION;
import static com.v7878.vmtools.JVMTIConstants.JVMTI_ERROR_WRONG_PHASE;
import static com.v7878.vmtools.JVMTIConstants.JVMTI_VERSION;
import static com.v7878.vmtools.JVMTIConstants.JVMTI_VERSION_1_2;

import android.util.Pair;

import androidx.annotation.Keep;

import com.v7878.foreign.AddressLayout;
import com.v7878.foreign.Arena;
import com.v7878.foreign.GroupLayout;
import com.v7878.foreign.MemoryLayout;
import com.v7878.foreign.MemorySegment;
import com.v7878.foreign.SymbolLookup;
import com.v7878.llvm.Types.LLVMTypeRef;
import com.v7878.llvm.Types.LLVMValueRef;
import com.v7878.unsafe.AndroidUnsafe;
import com.v7878.unsafe.ApiSensitive;
import com.v7878.unsafe.JNIUtils;
import com.v7878.unsafe.VM;
import com.v7878.unsafe.access.JavaForeignAccess;
import com.v7878.unsafe.foreign.BulkLinker;
import com.v7878.unsafe.foreign.BulkLinker.CallSignature;
import com.v7878.unsafe.foreign.BulkLinker.LibrarySymbol;
import com.v7878.unsafe.foreign.LibDLExt;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Optional;

public final class JVMTI {
    private JVMTI() {
    }

    static final Arena JVMTI_SCOPE = JavaForeignAccess.createImplicitHeapArena(JVMTI.class);

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
    public static final SymbolLookup JVMTI = LibDLExt.systemLibraryLookup(JVMTI_NAME, JVMTI_SCOPE);

    @Keep
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
        int version = CORRECT_SDK_INT <= 27 ? JVMTI_VERSION : kArtTiVersion;
        long JVM = getJavaVMPtr().nativeAddress();
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
    }

    public static MemorySegment getJVMTIEnvPtr() {
        class Holder {
            static final MemorySegment env = MemorySegment.ofAddress(JVMTI_ENV);
        }
        return Holder.env;
    }

    public static MemorySegment getJVMTIInterface() {
        class Holder {
            static final MemorySegment jvmti_interface =
                    JVMTI.findOrThrow("_ZN12openjdkjvmti15gJvmtiInterfaceE")
                            .reinterpret(JVMTI_INTERFACE_LAYOUT.byteSize());
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

    @Keep
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
            case JVMTI_ERROR_NONE -> "JVMTI_ERROR_NONE";
            case JVMTI_ERROR_INVALID_THREAD -> "JVMTI_ERROR_INVALID_THREAD";
            case JVMTI_ERROR_INVALID_THREAD_GROUP -> "JVMTI_ERROR_INVALID_THREAD_GROUP";
            case JVMTI_ERROR_INVALID_PRIORITY -> "JVMTI_ERROR_INVALID_PRIORITY";
            case JVMTI_ERROR_THREAD_NOT_SUSPENDED -> "JVMTI_ERROR_THREAD_NOT_SUSPENDED";
            case JVMTI_ERROR_THREAD_SUSPENDED -> "JVMTI_ERROR_THREAD_SUSPENDED";
            case JVMTI_ERROR_THREAD_NOT_ALIVE -> "JVMTI_ERROR_THREAD_NOT_ALIVE";
            case JVMTI_ERROR_INVALID_OBJECT -> "JVMTI_ERROR_INVALID_OBJECT";
            case JVMTI_ERROR_INVALID_CLASS -> "JVMTI_ERROR_INVALID_CLASS";
            case JVMTI_ERROR_CLASS_NOT_PREPARED -> "JVMTI_ERROR_CLASS_NOT_PREPARED";
            case JVMTI_ERROR_INVALID_METHODID -> "JVMTI_ERROR_INVALID_METHODID";
            case JVMTI_ERROR_INVALID_LOCATION -> "JVMTI_ERROR_INVALID_LOCATION";
            case JVMTI_ERROR_INVALID_FIELDID -> "JVMTI_ERROR_INVALID_FIELDID";
            case JVMTI_ERROR_NO_MORE_FRAMES -> "JVMTI_ERROR_NO_MORE_FRAMES";
            case JVMTI_ERROR_OPAQUE_FRAME -> "JVMTI_ERROR_OPAQUE_FRAME";
            case JVMTI_ERROR_TYPE_MISMATCH -> "JVMTI_ERROR_TYPE_MISMATCH";
            case JVMTI_ERROR_INVALID_SLOT -> "JVMTI_ERROR_INVALID_SLOT";
            case JVMTI_ERROR_DUPLICATE -> "JVMTI_ERROR_DUPLICATE";
            case JVMTI_ERROR_NOT_FOUND -> "JVMTI_ERROR_NOT_FOUND";
            case JVMTI_ERROR_INVALID_MONITOR -> "JVMTI_ERROR_INVALID_MONITOR";
            case JVMTI_ERROR_NOT_MONITOR_OWNER -> "JVMTI_ERROR_NOT_MONITOR_OWNER";
            case JVMTI_ERROR_INTERRUPT -> "JVMTI_ERROR_INTERRUPT";
            case JVMTI_ERROR_INVALID_CLASS_FORMAT -> "JVMTI_ERROR_INVALID_CLASS_FORMAT";
            case JVMTI_ERROR_CIRCULAR_CLASS_DEFINITION -> "JVMTI_ERROR_CIRCULAR_CLASS_DEFINITION";
            case JVMTI_ERROR_FAILS_VERIFICATION -> "JVMTI_ERROR_FAILS_VERIFICATION";
            case JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_ADDED ->
                    "JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_ADDED";
            case JVMTI_ERROR_UNSUPPORTED_REDEFINITION_SCHEMA_CHANGED ->
                    "JVMTI_ERROR_UNSUPPORTED_REDEFINITION_SCHEMA_CHANGED";
            case JVMTI_ERROR_INVALID_TYPESTATE -> "JVMTI_ERROR_INVALID_TYPESTATE";
            case JVMTI_ERROR_UNSUPPORTED_REDEFINITION_HIERARCHY_CHANGED ->
                    "JVMTI_ERROR_UNSUPPORTED_REDEFINITION_HIERARCHY_CHANGED";
            case JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_DELETED ->
                    "JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_DELETED";
            case JVMTI_ERROR_UNSUPPORTED_VERSION -> "JVMTI_ERROR_UNSUPPORTED_VERSION";
            case JVMTI_ERROR_NAMES_DONT_MATCH -> "JVMTI_ERROR_NAMES_DONT_MATCH";
            case JVMTI_ERROR_UNSUPPORTED_REDEFINITION_CLASS_MODIFIERS_CHANGED ->
                    "JVMTI_ERROR_UNSUPPORTED_REDEFINITION_CLASS_MODIFIERS_CHANGED";
            case JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_MODIFIERS_CHANGED ->
                    "JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_MODIFIERS_CHANGED";
            case JVMTI_ERROR_UNMODIFIABLE_CLASS -> "JVMTI_ERROR_UNMODIFIABLE_CLASS";
            case JVMTI_ERROR_NOT_AVAILABLE -> "JVMTI_ERROR_NOT_AVAILABLE";
            case JVMTI_ERROR_MUST_POSSESS_CAPABILITY -> "JVMTI_ERROR_MUST_POSSESS_CAPABILITY";
            case JVMTI_ERROR_NULL_POINTER -> "JVMTI_ERROR_NULL_POINTER";
            case JVMTI_ERROR_ABSENT_INFORMATION -> "JVMTI_ERROR_ABSENT_INFORMATION";
            case JVMTI_ERROR_INVALID_EVENT_TYPE -> "JVMTI_ERROR_INVALID_EVENT_TYPE";
            case JVMTI_ERROR_ILLEGAL_ARGUMENT -> "JVMTI_ERROR_ILLEGAL_ARGUMENT";
            case JVMTI_ERROR_NATIVE_METHOD -> "JVMTI_ERROR_NATIVE_METHOD";
            case JVMTI_ERROR_CLASS_LOADER_UNSUPPORTED -> "JVMTI_ERROR_CLASS_LOADER_UNSUPPORTED";
            case JVMTI_ERROR_OUT_OF_MEMORY -> "JVMTI_ERROR_OUT_OF_MEMORY";
            case JVMTI_ERROR_ACCESS_DENIED -> "JVMTI_ERROR_ACCESS_DENIED";
            case JVMTI_ERROR_WRONG_PHASE -> "JVMTI_ERROR_WRONG_PHASE";
            case JVMTI_ERROR_INTERNAL -> "JVMTI_ERROR_INTERNAL";
            case JVMTI_ERROR_UNATTACHED_THREAD -> "JVMTI_ERROR_UNATTACHED_THREAD";
            case JVMTI_ERROR_INVALID_ENVIRONMENT -> "JVMTI_ERROR_INVALID_ENVIRONMENT";
            default -> "Unknown JVMTI_ERROR code: " + error;
        };
    }

    @SafeVarargs
    public static void RedefineClasses(Pair<Class<?>, byte[]>... class_definitions) {
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

    public static void RedefineClass(Class<?> klass, byte[] data) {
        RedefineClasses(new Pair<>(klass, data));
    }

    public static long GetObjectSize(Object obj) throws JVMTIException {
        try (Arena scope = Arena.ofConfined()) {
            MemorySegment size_ptr = scope.allocate(JAVA_LONG);
            checkError(Native.INSTANCE.GetObjectSize(JVMTI_ENV, obj, size_ptr.nativeAddress()));
            return size_ptr.get(JAVA_LONG, 0);
        }
    }

    public static void ForceAllPotentialCapabilities() throws JVMTIException {
        class Holder {
            private static final long ALL_CAPS = (~0L) >>> (64 - 41);
            private static final long GPC_OFFSET = JVMTI_INTERFACE_LAYOUT.
                    byteOffset(groupElement("GetPotentialCapabilities"));
            static final MemorySegment INTERFACE_COPY =
                    JVMTI_SCOPE.allocate(JVMTI_INTERFACE_LAYOUT);

            static {
                final String name = "function";
                MemorySegment getter = generateFunctionCodeSegment((context, module, builder) -> {
                    LLVMTypeRef[] arg_types = {intptr_t(context), ptr_t(int128_t(context))};
                    LLVMTypeRef ret_type = int32_t(context);
                    LLVMTypeRef f_type = LLVMFunctionType(ret_type, arg_types, false);
                    LLVMValueRef function = LLVMAddFunction(module, name, f_type);
                    LLVMValueRef[] args = LLVMGetParams(function);

                    LLVMPositionBuilderAtEnd(builder, LLVMAppendBasicBlock(function, ""));
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

    static void SetEventCallbacks(long callbacks, int size_of_callbacks) throws JVMTIException {
        checkError(Native.INSTANCE.SetEventCallbacks(JVMTI_ENV, callbacks, size_of_callbacks));
    }

    public void SetEventNotificationMode(int mode, int event_type, Thread event_thread) throws JVMTIException {
        checkError(Native.INSTANCE.SetEventNotificationMode(JVMTI_ENV, mode, event_type, event_thread));
    }

    public void SetBreakpoint(Method method, long location) throws JVMTIException {
        checkError(Native.INSTANCE.SetBreakpoint(JVMTI_ENV, JNIUtils.FromReflectedMethod(method), location));
    }

    public void ClearBreakpoint(Method method, long location) throws JVMTIException {
        checkError(Native.INSTANCE.ClearBreakpoint(JVMTI_ENV, JNIUtils.FromReflectedMethod(method), location));
    }
}

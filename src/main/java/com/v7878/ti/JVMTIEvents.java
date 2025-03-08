package com.v7878.ti;

import static com.v7878.foreign.Linker.Option.JNIEnvArg;
import static com.v7878.foreign.Linker.Option.allowExceptions;
import static com.v7878.foreign.MemoryLayout.PathElement.groupElement;
import static com.v7878.foreign.MemoryLayout.structLayout;
import static com.v7878.foreign.ValueLayout.ADDRESS;
import static com.v7878.foreign.ValueLayout.JAVA_BOOLEAN;
import static com.v7878.ti.JVMTI.JVMTI_SCOPE;
import static com.v7878.ti.JVMTIConstants.JVMTI_DISABLE;
import static com.v7878.ti.JVMTIConstants.JVMTI_ENABLE;
import static com.v7878.ti.JVMTIConstants.JVMTI_EVENT_BREAKPOINT;
import static com.v7878.ti.JVMTIConstants.JVMTI_EVENT_CLASS_LOAD;
import static com.v7878.ti.JVMTIConstants.JVMTI_EVENT_CLASS_PREPARE;
import static com.v7878.ti.JVMTIConstants.JVMTI_EVENT_GARBAGE_COLLECTION_FINISH;
import static com.v7878.ti.JVMTIConstants.JVMTI_EVENT_GARBAGE_COLLECTION_START;
import static com.v7878.ti.JVMTIConstants.JVMTI_EVENT_MONITOR_CONTENDED_ENTER;
import static com.v7878.ti.JVMTIConstants.JVMTI_EVENT_MONITOR_CONTENDED_ENTERED;
import static com.v7878.ti.JVMTIConstants.JVMTI_EVENT_MONITOR_WAIT;
import static com.v7878.ti.JVMTIConstants.JVMTI_EVENT_MONITOR_WAITED;
import static com.v7878.unsafe.AndroidUnsafe.IS64BIT;
import static com.v7878.unsafe.cpp_std.CLayouts.C_LONG;
import static com.v7878.unsafe.foreign.ExtraLayouts.WORD;

import com.v7878.foreign.FunctionDescriptor;
import com.v7878.foreign.GroupLayout;
import com.v7878.foreign.Linker;
import com.v7878.foreign.MemorySegment;
import com.v7878.unsafe.JNIUtils;
import com.v7878.unsafe.invoke.EmulatedStackFrame;
import com.v7878.unsafe.invoke.EmulatedStackFrame.StackFrameAccessor;
import com.v7878.unsafe.invoke.Transformers;
import com.v7878.unsafe.invoke.Transformers.AbstractTransformer;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;

public class JVMTIEvents {
    @FunctionalInterface
    public interface BreakpointCallback {
        void invoke(Thread thread, Method method, long location);
    }

    @FunctionalInterface
    public interface GarbageCollectionCallback {
        void invoke(boolean start_or_finish);
    }

    @FunctionalInterface
    public interface ClassLoadCallback {
        void invoke(Thread thread, Class<?> klass);
    }

    @FunctionalInterface
    public interface ClassPrepareCallback {
        void invoke(Thread thread, Class<?> klass);
    }

    @FunctionalInterface
    public interface MonitorContendedEnterCallback {
        void invoke(Thread thread, Object object);
    }

    @FunctionalInterface
    public interface MonitorContendedEnteredCallback {
        void invoke(Thread thread, Object object);
    }

    @FunctionalInterface
    public interface MonitorWaitCallback {
        void invoke(Thread thread, Object object, long timeout);
    }

    @FunctionalInterface
    public interface MonitorWaitedCallback {
        void invoke(Thread thread, Object object, boolean timed_out);
    }

    // TODO
    //typedef void (JNICALL *jvmtiEventClassFileLoadHook)
    //    (jvmtiEnv *jvmti_env,
    //     JNIEnv* jni_env,
    //     jclass class_being_redefined,
    //     jobject loader,
    //     const char* name,
    //     jobject protection_domain,
    //     jint class_data_len,
    //     const unsigned char* class_data,
    //     jint* new_class_data_len,
    //     unsigned char** new_class_data);
    //typedef void (JNICALL *jvmtiEventException)
    //    (jvmtiEnv *jvmti_env,
    //     JNIEnv* jni_env,
    //     jthread thread,
    //     jmethodID method,
    //     jlocation location,
    //     jobject exception,
    //     jmethodID catch_method,
    //     jlocation catch_location);
    //typedef void (JNICALL *jvmtiEventExceptionCatch)
    //    (jvmtiEnv *jvmti_env,
    //     JNIEnv* jni_env,
    //     jthread thread,
    //     jmethodID method,
    //     jlocation location,
    //     jobject exception);
    //typedef void (JNICALL *jvmtiEventFieldAccess)
    //    (jvmtiEnv *jvmti_env,
    //     JNIEnv* jni_env,
    //     jthread thread,
    //     jmethodID method,
    //     jlocation location,
    //     jclass field_klass,
    //     jobject object,
    //     jfieldID field);
    //typedef void (JNICALL *jvmtiEventFieldModification)
    //    (jvmtiEnv *jvmti_env,
    //     JNIEnv* jni_env,
    //     jthread thread,
    //     jmethodID method,
    //     jlocation location,
    //     jclass field_klass,
    //     jobject object,
    //     jfieldID field,
    //     char signature_type,
    //     jvalue new_value);
    //typedef void (JNICALL *jvmtiEventFramePop)
    //    (jvmtiEnv *jvmti_env,
    //     JNIEnv* jni_env,
    //     jthread thread,
    //     jmethodID method,
    //     jboolean was_popped_by_exception);
    //typedef void (JNICALL *jvmtiEventMethodEntry)
    //    (jvmtiEnv *jvmti_env,
    //     JNIEnv* jni_env,
    //     jthread thread,
    //     jmethodID method);
    //typedef void (JNICALL *jvmtiEventMethodExit)
    //    (jvmtiEnv *jvmti_env,
    //     JNIEnv* jni_env,
    //     jthread thread,
    //     jmethodID method,
    //     jboolean was_popped_by_exception,
    //     jvalue return_value);
    //typedef void (JNICALL *jvmtiEventNativeMethodBind)
    //    (jvmtiEnv *jvmti_env,
    //     JNIEnv* jni_env,
    //     jthread thread,
    //     jmethodID method,
    //     void* address,
    //     void** new_address_ptr);
    //typedef void (JNICALL *jvmtiEventVMObjectAlloc)
    //    (jvmtiEnv *jvmti_env,
    //     JNIEnv* jni_env,
    //     jthread thread,
    //     jobject object,
    //     jclass object_klass,
    //     jlong size);
    //typedef void (JNICALL *jvmtiEventObjectFree)
    //    (jvmtiEnv *jvmti_env,
    //     jlong tag);
    //typedef void (JNICALL *jvmtiEventSingleStep)
    //    (jvmtiEnv *jvmti_env,
    //     JNIEnv* jni_env,
    //     jthread thread,
    //     jmethodID method,
    //     jlocation location);
    //typedef void (JNICALL *jvmtiEventThreadEnd)
    //    (jvmtiEnv *jvmti_env,
    //     JNIEnv* jni_env,
    //     jthread thread);
    //typedef void (JNICALL *jvmtiEventThreadStart)
    //    (jvmtiEnv *jvmti_env,
    //     JNIEnv* jni_env,
    //     jthread thread);

    public static final GroupLayout JVMTI_EVENT_CALLBACKS_LAYOUT = structLayout(
            ADDRESS.withName("VMInit"),
            ADDRESS.withName("VMDeath"),
            ADDRESS.withName("ThreadStart"),
            ADDRESS.withName("ThreadEnd"),
            ADDRESS.withName("ClassFileLoadHook"),
            ADDRESS.withName("ClassLoad"),
            ADDRESS.withName("ClassPrepare"),
            ADDRESS.withName("VMStart"),
            ADDRESS.withName("Exception"),
            ADDRESS.withName("ExceptionCatch"),
            ADDRESS.withName("SingleStep"),
            ADDRESS.withName("FramePop"),
            ADDRESS.withName("Breakpoint"),
            ADDRESS.withName("FieldAccess"),
            ADDRESS.withName("FieldModification"),
            ADDRESS.withName("MethodEntry"),
            ADDRESS.withName("MethodExit"),
            ADDRESS.withName("NativeMethodBind"),
            ADDRESS.withName("CompiledMethodLoad"),
            ADDRESS.withName("CompiledMethodUnload"),
            ADDRESS.withName("DynamicCodeGenerated"),
            ADDRESS.withName("DataDumpRequest"),
            ADDRESS.withName("reserved72"),
            ADDRESS.withName("MonitorWait"),
            ADDRESS.withName("MonitorWaited"),
            ADDRESS.withName("MonitorContendedEnter"),
            ADDRESS.withName("MonitorContendedEntered"),
            ADDRESS.withName("reserved77"),
            ADDRESS.withName("reserved78"),
            ADDRESS.withName("reserved79"),
            ADDRESS.withName("ResourceExhausted"),
            ADDRESS.withName("GarbageCollectionStart"),
            ADDRESS.withName("GarbageCollectionFinish"),
            ADDRESS.withName("ObjectFree"),
            ADDRESS.withName("VMObjectAlloc")
    );

    private static final Linker LINKER = Linker.nativeLinker();
    private static final MemorySegment CALLBACKS =
            JVMTI_SCOPE.allocate(JVMTI_EVENT_CALLBACKS_LAYOUT);
    private static final long CALLBACKS_ADDRESS = CALLBACKS.nativeAddress();
    private static final int CALLBACKS_SIZE = Math.toIntExact(CALLBACKS.byteSize());

    private static void updateMode(boolean enable, int event_type, Thread event_thread) {
        JVMTI.SetEventNotificationMode(enable ? JVMTI_ENABLE : JVMTI_DISABLE, event_type, event_thread);
    }

    private static void updateCallbacks(boolean enable, long offset, MemorySegment value) {
        CALLBACKS.set(ADDRESS, offset, enable ? value : MemorySegment.NULL);
        JVMTI.SetEventCallbacks(CALLBACKS_ADDRESS, CALLBACKS_SIZE);
    }

    private static long callbackOffset(String name) {
        return JVMTI_EVENT_CALLBACKS_LAYOUT.byteOffset(groupElement(name));
    }

    private static long getWord(StackFrameAccessor accessor, int index) {
        return IS64BIT ? accessor.getLong(index) : accessor.getInt(index) & 0xffffffffL;
    }

    public static void setBreakpointCallback(BreakpointCallback callback, Thread event_thread) {
        class Holder {
            static volatile BreakpointCallback java_callback;
            static final long OFFSET = callbackOffset("Breakpoint");
            // (jvmtiEnv*, JNIEnv*, jthread, jmethodID, jlocation) -> void
            static final FunctionDescriptor DESCRIPTOR =
                    FunctionDescriptor.ofVoid(WORD, WORD, WORD, C_LONG);
            static final MethodHandle HANDLE = Transformers.makeTransformer(
                    DESCRIPTOR.toMethodType(), new AbstractTransformer() {
                        @Override
                        protected void transform(MethodHandle thiz, EmulatedStackFrame stack) {
                            var tmp_callback = java_callback;
                            if (tmp_callback == null) return;
                            var accessor = stack.accessor();
                            var thread = (Thread) JNIUtils.refToObject(getWord(accessor, 1));
                            var method = JNIUtils.ToReflectedMethod(getWord(accessor, 2));
                            var location = accessor.getLong(3);
                            tmp_callback.invoke(thread, method, location);
                        }
                    });
            static final MemorySegment native_callback = LINKER.upcallStub(
                    HANDLE, DESCRIPTOR, JVMTI_SCOPE, JNIEnvArg(1), allowExceptions());
        }
        updateMode(callback != null, JVMTI_EVENT_BREAKPOINT, event_thread);
        Holder.java_callback = callback;
        updateCallbacks(callback != null, Holder.OFFSET, Holder.native_callback);
    }

    public static void setBreakpointCallback(BreakpointCallback callback) {
        setBreakpointCallback(callback, null);
    }

    public static void setGarbageCollectionCallback(GarbageCollectionCallback callback, Thread event_thread) {
        class Holder {
            static volatile GarbageCollectionCallback java_callback;
            static final long START_OFFSET = callbackOffset("GarbageCollectionStart");
            static final long FINISH_OFFSET = callbackOffset("GarbageCollectionFinish");
            // (jvmtiEnv *jvmti_env) -> void
            static final FunctionDescriptor DESCRIPTOR = FunctionDescriptor.ofVoid(WORD);

            static class Handle extends AbstractTransformer {
                private final boolean start_or_finish;

                Handle(boolean start_or_finish) {
                    this.start_or_finish = start_or_finish;
                }

                @Override
                protected void transform(MethodHandle thiz, EmulatedStackFrame stack) {
                    var tmp_callback = java_callback;
                    if (tmp_callback == null) return;
                    tmp_callback.invoke(start_or_finish);
                }
            }

            static final MethodHandle START_HANDLE = Transformers.makeTransformer(
                    DESCRIPTOR.toMethodType(), new Handle(true));
            static final MemorySegment start_native_callback = LINKER.upcallStub(
                    START_HANDLE, DESCRIPTOR, JVMTI_SCOPE, allowExceptions());
            static final MethodHandle FINISH_HANDLE = Transformers.makeTransformer(
                    DESCRIPTOR.toMethodType(), new Handle(false));
            static final MemorySegment finish_native_callback = LINKER.upcallStub(
                    FINISH_HANDLE, DESCRIPTOR, JVMTI_SCOPE, allowExceptions());
        }
        updateMode(callback != null, JVMTI_EVENT_GARBAGE_COLLECTION_START, event_thread);
        updateMode(callback != null, JVMTI_EVENT_GARBAGE_COLLECTION_FINISH, event_thread);
        Holder.java_callback = callback;
        updateCallbacks(callback != null, Holder.START_OFFSET, Holder.start_native_callback);
        updateCallbacks(callback != null, Holder.FINISH_OFFSET, Holder.finish_native_callback);
    }

    public static void setGarbageCollectionCallback(GarbageCollectionCallback callback) {
        setGarbageCollectionCallback(callback, null);
    }

    public static void setClassLoadCallback(ClassLoadCallback callback, Thread event_thread) {
        class Holder {
            static volatile ClassLoadCallback java_callback;
            static final long OFFSET = callbackOffset("ClassLoad");
            // (jvmtiEnv*, JNIEnv*, jthread, jclass) -> void
            static final FunctionDescriptor DESCRIPTOR =
                    FunctionDescriptor.ofVoid(WORD, WORD, WORD);
            static final MethodHandle HANDLE = Transformers.makeTransformer(
                    DESCRIPTOR.toMethodType(), new AbstractTransformer() {
                        @Override
                        protected void transform(MethodHandle thiz, EmulatedStackFrame stack) {
                            var tmp_callback = java_callback;
                            if (tmp_callback == null) return;
                            var accessor = stack.accessor();
                            var thread = (Thread) JNIUtils.refToObject(getWord(accessor, 1));
                            var klass = (Class<?>) JNIUtils.refToObject(getWord(accessor, 2));
                            tmp_callback.invoke(thread, klass);
                        }
                    });
            static final MemorySegment native_callback = LINKER.upcallStub(
                    HANDLE, DESCRIPTOR, JVMTI_SCOPE, JNIEnvArg(1), allowExceptions());
        }
        updateMode(callback != null, JVMTI_EVENT_CLASS_LOAD, event_thread);
        Holder.java_callback = callback;
        updateCallbacks(callback != null, Holder.OFFSET, Holder.native_callback);
    }

    public static void setClassLoadCallback(ClassLoadCallback callback) {
        setClassLoadCallback(callback, null);
    }

    public static void setClassPrepareCallback(ClassPrepareCallback callback, Thread event_thread) {
        class Holder {
            static volatile ClassPrepareCallback java_callback;
            static final long OFFSET = callbackOffset("ClassPrepare");
            // (jvmtiEnv*, JNIEnv*, jthread, jclass) -> void
            static final FunctionDescriptor DESCRIPTOR =
                    FunctionDescriptor.ofVoid(WORD, WORD, WORD);
            static final MethodHandle HANDLE = Transformers.makeTransformer(
                    DESCRIPTOR.toMethodType(), new AbstractTransformer() {
                        @Override
                        protected void transform(MethodHandle thiz, EmulatedStackFrame stack) {
                            var tmp_callback = java_callback;
                            if (tmp_callback == null) return;
                            var accessor = stack.accessor();
                            var thread = (Thread) JNIUtils.refToObject(getWord(accessor, 1));
                            var klass = (Class<?>) JNIUtils.refToObject(getWord(accessor, 2));
                            tmp_callback.invoke(thread, klass);
                        }
                    });
            static final MemorySegment native_callback = LINKER.upcallStub(
                    HANDLE, DESCRIPTOR, JVMTI_SCOPE, JNIEnvArg(1), allowExceptions());
        }
        updateMode(callback != null, JVMTI_EVENT_CLASS_PREPARE, event_thread);
        Holder.java_callback = callback;
        updateCallbacks(callback != null, Holder.OFFSET, Holder.native_callback);
    }

    public static void setClassPrepareCallback(ClassPrepareCallback callback) {
        setClassPrepareCallback(callback, null);
    }

    public static void setMonitorContendedEnterCallback(MonitorContendedEnterCallback callback, Thread event_thread) {
        class Holder {
            static volatile MonitorContendedEnterCallback java_callback;
            static final long OFFSET = callbackOffset("MonitorContendedEnter");
            // (jvmtiEnv*, JNIEnv*, jthread, jobject) -> void
            static final FunctionDescriptor DESCRIPTOR =
                    FunctionDescriptor.ofVoid(WORD, WORD, WORD);
            static final MethodHandle HANDLE = Transformers.makeTransformer(
                    DESCRIPTOR.toMethodType(), new AbstractTransformer() {
                        @Override
                        protected void transform(MethodHandle thiz, EmulatedStackFrame stack) {
                            var tmp_callback = java_callback;
                            if (tmp_callback == null) return;
                            var accessor = stack.accessor();
                            var thread = (Thread) JNIUtils.refToObject(getWord(accessor, 1));
                            var object = JNIUtils.refToObject(getWord(accessor, 2));
                            tmp_callback.invoke(thread, object);
                        }
                    });
            static final MemorySegment native_callback = LINKER.upcallStub(
                    HANDLE, DESCRIPTOR, JVMTI_SCOPE, JNIEnvArg(1), allowExceptions());
        }
        updateMode(callback != null, JVMTI_EVENT_MONITOR_CONTENDED_ENTER, event_thread);
        Holder.java_callback = callback;
        updateCallbacks(callback != null, Holder.OFFSET, Holder.native_callback);
    }

    public static void setMonitorContendedEnterCallback(MonitorContendedEnterCallback callback) {
        setMonitorContendedEnterCallback(callback, null);
    }

    public static void setMonitorContendedEnteredCallback(MonitorContendedEnteredCallback callback, Thread event_thread) {
        class Holder {
            static volatile MonitorContendedEnteredCallback java_callback;
            static final long OFFSET = callbackOffset("MonitorContendedEntered");
            // (jvmtiEnv*, JNIEnv*, jthread, jobject) -> void
            static final FunctionDescriptor DESCRIPTOR =
                    FunctionDescriptor.ofVoid(WORD, WORD, WORD);
            static final MethodHandle HANDLE = Transformers.makeTransformer(
                    DESCRIPTOR.toMethodType(), new AbstractTransformer() {
                        @Override
                        protected void transform(MethodHandle thiz, EmulatedStackFrame stack) {
                            var tmp_callback = java_callback;
                            if (tmp_callback == null) return;
                            var accessor = stack.accessor();
                            var thread = (Thread) JNIUtils.refToObject(getWord(accessor, 1));
                            var object = JNIUtils.refToObject(getWord(accessor, 2));
                            tmp_callback.invoke(thread, object);
                        }
                    });
            static final MemorySegment native_callback = LINKER.upcallStub(
                    HANDLE, DESCRIPTOR, JVMTI_SCOPE, JNIEnvArg(1), allowExceptions());
        }
        updateMode(callback != null, JVMTI_EVENT_MONITOR_CONTENDED_ENTERED, event_thread);
        Holder.java_callback = callback;
        updateCallbacks(callback != null, Holder.OFFSET, Holder.native_callback);
    }

    public static void setMonitorContendedEnteredCallback(MonitorContendedEnteredCallback callback) {
        setMonitorContendedEnteredCallback(callback, null);
    }

    public static void setMonitorWaitCallback(MonitorWaitCallback callback, Thread event_thread) {
        class Holder {
            static volatile MonitorWaitCallback java_callback;
            static final long OFFSET = callbackOffset("MonitorWait");
            // (jvmtiEnv*, JNIEnv*, jthread, jobject, jlong) -> void
            static final FunctionDescriptor DESCRIPTOR =
                    FunctionDescriptor.ofVoid(WORD, WORD, WORD, C_LONG);
            static final MethodHandle HANDLE = Transformers.makeTransformer(
                    DESCRIPTOR.toMethodType(), new AbstractTransformer() {
                        @Override
                        protected void transform(MethodHandle thiz, EmulatedStackFrame stack) {
                            var tmp_callback = java_callback;
                            if (tmp_callback == null) return;
                            var accessor = stack.accessor();
                            var thread = (Thread) JNIUtils.refToObject(getWord(accessor, 1));
                            var object = JNIUtils.refToObject(getWord(accessor, 2));
                            var timeout = accessor.getLong(3);
                            tmp_callback.invoke(thread, object, timeout);
                        }
                    });
            static final MemorySegment native_callback = LINKER.upcallStub(
                    HANDLE, DESCRIPTOR, JVMTI_SCOPE, JNIEnvArg(1), allowExceptions());
        }
        updateMode(callback != null, JVMTI_EVENT_MONITOR_WAIT, event_thread);
        Holder.java_callback = callback;
        updateCallbacks(callback != null, Holder.OFFSET, Holder.native_callback);
    }

    public static void setMonitorWaitCallback(MonitorWaitCallback callback) {
        setMonitorWaitCallback(callback, null);
    }

    public static void setMonitorWaitedCallback(MonitorWaitedCallback callback, Thread event_thread) {
        class Holder {
            static volatile MonitorWaitedCallback java_callback;
            static final long OFFSET = callbackOffset("MonitorWaited");
            // (jvmtiEnv*, JNIEnv*, jthread, jobject, jboolean) -> void
            static final FunctionDescriptor DESCRIPTOR =
                    FunctionDescriptor.ofVoid(WORD, WORD, WORD, JAVA_BOOLEAN);
            static final MethodHandle HANDLE = Transformers.makeTransformer(
                    DESCRIPTOR.toMethodType(), new AbstractTransformer() {
                        @Override
                        protected void transform(MethodHandle thiz, EmulatedStackFrame stack) {
                            var tmp_callback = java_callback;
                            if (tmp_callback == null) return;
                            var accessor = stack.accessor();
                            var thread = (Thread) JNIUtils.refToObject(getWord(accessor, 1));
                            var object = JNIUtils.refToObject(getWord(accessor, 2));
                            var timed_out = accessor.getBoolean(3);
                            tmp_callback.invoke(thread, object, timed_out);
                        }
                    });
            static final MemorySegment native_callback = LINKER.upcallStub(
                    HANDLE, DESCRIPTOR, JVMTI_SCOPE, JNIEnvArg(1), allowExceptions());
        }
        updateMode(callback != null, JVMTI_EVENT_MONITOR_WAITED, event_thread);
        Holder.java_callback = callback;
        updateCallbacks(callback != null, Holder.OFFSET, Holder.native_callback);
    }

    public static void setMonitorWaitedCallback(MonitorWaitedCallback callback) {
        setMonitorWaitedCallback(callback, null);
    }
}

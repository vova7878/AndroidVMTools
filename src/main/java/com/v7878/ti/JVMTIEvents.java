package com.v7878.ti;

import static com.v7878.foreign.Linker.Option.JNIEnvArg;
import static com.v7878.foreign.Linker.Option.allowExceptions;
import static com.v7878.foreign.MemoryLayout.PathElement.groupElement;
import static com.v7878.foreign.MemoryLayout.structLayout;
import static com.v7878.foreign.ValueLayout.ADDRESS;
import static com.v7878.foreign.ValueLayout.JAVA_LONG;
import static com.v7878.ti.JVMTI.JVMTI_SCOPE;
import static com.v7878.unsafe.AndroidUnsafe.IS64BIT;
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
    //typedef void (JNICALL *jvmtiEventClassLoad)
    //    (jvmtiEnv *jvmti_env,
    //     JNIEnv* jni_env,
    //     jthread thread,
    //     jclass klass);
    //typedef void (JNICALL *jvmtiEventClassPrepare)
    //    (jvmtiEnv *jvmti_env,
    //     JNIEnv* jni_env,
    //     jthread thread,
    //     jclass klass);
    //typedef void (JNICALL *jvmtiEventDataDumpRequest)
    //    (jvmtiEnv *jvmti_env);
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
    //typedef void (JNICALL *jvmtiEventMonitorContendedEnter)
    //    (jvmtiEnv *jvmti_env,
    //     JNIEnv* jni_env,
    //     jthread thread,
    //     jobject object);
    //typedef void (JNICALL *jvmtiEventMonitorContendedEntered)
    //    (jvmtiEnv *jvmti_env,
    //     JNIEnv* jni_env,
    //     jthread thread,
    //     jobject object);
    //typedef void (JNICALL *jvmtiEventMonitorWait)
    //    (jvmtiEnv *jvmti_env,
    //     JNIEnv* jni_env,
    //     jthread thread,
    //     jobject object,
    //     jlong timeout);
    //typedef void (JNICALL *jvmtiEventMonitorWaited)
    //    (jvmtiEnv *jvmti_env,
    //     JNIEnv* jni_env,
    //     jthread thread,
    //     jobject object,
    //     jboolean timed_out);
    //typedef void (JNICALL *jvmtiEventNativeMethodBind)
    //    (jvmtiEnv *jvmti_env,
    //     JNIEnv* jni_env,
    //     jthread thread,
    //     jmethodID method,
    //     void* address,
    //     void** new_address_ptr);
    //typedef void (JNICALL *jvmtiEventObjectFree)
    //    (jvmtiEnv *jvmti_env,
    //     jlong tag);
    //typedef void (JNICALL *jvmtiEventResourceExhausted)
    //    (jvmtiEnv *jvmti_env,
    //     JNIEnv* jni_env,
    //     jint flags,
    //     const void* reserved,
    //     const char* description);
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
    //typedef void (JNICALL *jvmtiEventVMDeath)
    //    (jvmtiEnv *jvmti_env,
    //     JNIEnv* jni_env);
    //typedef void (JNICALL *jvmtiEventVMInit)
    //    (jvmtiEnv *jvmti_env,
    //     JNIEnv* jni_env,
    //     jthread thread);
    //typedef void (JNICALL *jvmtiEventVMObjectAlloc)
    //    (jvmtiEnv *jvmti_env,
    //     JNIEnv* jni_env,
    //     jthread thread,
    //     jobject object,
    //     jclass object_klass,
    //     jlong size);
    //typedef void (JNICALL *jvmtiEventVMStart)
    //    (jvmtiEnv *jvmti_env,
    //     JNIEnv* jni_env);

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

    private static long callbackOffset(String name) {
        return JVMTI_EVENT_CALLBACKS_LAYOUT.byteOffset(groupElement(name));
    }

    private static long getWord(StackFrameAccessor accessor, int index) {
        return IS64BIT ? accessor.getLong(index) : accessor.getInt(index) & 0xffffffffL;
    }

    // (jvmtiEnv*, JNIEnv*, jthread, jmethodID, jlocation) -> void
    public static void setBreakpointCallback(BreakpointCallback callback) {
        class Holder {
            static volatile BreakpointCallback java_callback;
            static final long OFFSET = callbackOffset("Breakpoint");
            static final FunctionDescriptor DESCRIPTOR =
                    FunctionDescriptor.ofVoid(WORD, WORD, WORD, JAVA_LONG);
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
        Holder.java_callback = callback;
        CALLBACKS.set(ADDRESS, Holder.OFFSET, callback == null ?
                MemorySegment.NULL : Holder.native_callback);
        JVMTI.SetEventCallbacks(CALLBACKS_ADDRESS, CALLBACKS_SIZE);
    }

    // (jvmtiEnv *jvmti_env) -> void
    public static void setGarbageCollectionCallback(GarbageCollectionCallback callback) {
        class Holder {
            static volatile GarbageCollectionCallback java_callback;
            static final long START_OFFSET = callbackOffset("GarbageCollectionStart");
            static final long FINISH_OFFSET = callbackOffset("GarbageCollectionFinish");
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
        Holder.java_callback = callback;
        CALLBACKS.set(ADDRESS, Holder.START_OFFSET, callback == null ?
                MemorySegment.NULL : Holder.start_native_callback);
        CALLBACKS.set(ADDRESS, Holder.FINISH_OFFSET, callback == null ?
                MemorySegment.NULL : Holder.finish_native_callback);
        JVMTI.SetEventCallbacks(CALLBACKS_ADDRESS, CALLBACKS_SIZE);
    }
}

package com.v7878.ti;

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
import com.v7878.unsafe.invoke.EmulatedStackFrame.StackFrameAccessor;
import com.v7878.unsafe.invoke.Transformers;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;

public class JVMTIEvents {
    @FunctionalInterface
    public interface BreakpointCallback {
        void invoke(Thread thread, Method method, long location);
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
    //typedef void (JNICALL *jvmtiEventCompiledMethodLoad)
    //    (jvmtiEnv *jvmti_env,
    //     jmethodID method,
    //     jint code_size,
    //     const void* code_addr,
    //     jint map_length,
    //     const jvmtiAddrLocationMap* map,
    //     const void* compile_info);
    //typedef void (JNICALL *jvmtiEventCompiledMethodUnload)
    //    (jvmtiEnv *jvmti_env,
    //     jmethodID method,
    //     const void* code_addr);
    //typedef void (JNICALL *jvmtiEventDataDumpRequest)
    //    (jvmtiEnv *jvmti_env);
    //typedef void (JNICALL *jvmtiEventDynamicCodeGenerated)
    //    (jvmtiEnv *jvmti_env,
    //     const char* name,
    //     const void* address,
    //     jint length);
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
    //typedef void (JNICALL *jvmtiEventGarbageCollectionFinish)
    //    (jvmtiEnv *jvmti_env);
    //typedef void (JNICALL *jvmtiEventGarbageCollectionStart)
    //    (jvmtiEnv *jvmti_env);
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

    private static final Class<?> jword = IS64BIT ? long.class : int.class;

    private static long nextWord(StackFrameAccessor accessor) {
        return IS64BIT ? accessor.nextLong() : accessor.nextInt() & 0xffffffffL;
    }

    public static void setBreakpointCallback(BreakpointCallback callback) {
        //TODO: change upcall to hand-written stub (because callback has jni env already)
        class Holder {
            static BreakpointCallback java_callback;
            static final long OFFSET = JVMTI_EVENT_CALLBACKS_LAYOUT
                    .byteOffset(groupElement("Breakpoint"));
            // (jvmtiEnv*, JNIEnv*, jthread, jmethodID, jlocation) -> void
            static final MethodType TYPE = MethodType.methodType(
                    void.class, jword, jword, jword, jword, long.class);
            static final FunctionDescriptor DESCRIPTOR = FunctionDescriptor.ofVoid(
                    WORD, WORD, WORD, WORD, JAVA_LONG);
            static final MethodHandle invoker = Transformers.makeTransformer(
                    TYPE, (thiz, stack) -> {
                        var tmp_callback = java_callback;
                        if (tmp_callback == null) {
                            return;
                        }
                        var accessor = stack.createAccessor();
                        nextWord(accessor); // jvmti_env
                        nextWord(accessor); // jni_env
                        var thread = (Thread) JNIUtils.refToObject(nextWord(accessor));
                        var method = JNIUtils.ToReflectedMethod(nextWord(accessor));
                        var location = accessor.nextLong();
                        tmp_callback.invoke(thread, method, location);
                    });
            static final MemorySegment native_callback = LINKER.upcallStub(
                    invoker, DESCRIPTOR, JVMTI_SCOPE);
        }
        Holder.java_callback = callback;
        CALLBACKS.set(ADDRESS, Holder.OFFSET, callback == null ?
                MemorySegment.NULL : Holder.native_callback);
        JVMTI.SetEventCallbacks(CALLBACKS_ADDRESS, CALLBACKS_SIZE);
    }
}

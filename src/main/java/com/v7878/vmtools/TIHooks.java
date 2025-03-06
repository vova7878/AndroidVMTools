package com.v7878.vmtools;

import static com.v7878.dex.DexConstants.ACC_ABSTRACT;
import static com.v7878.dex.DexConstants.ACC_FINAL;
import static com.v7878.dex.DexConstants.ACC_NATIVE;
import static com.v7878.dex.DexConstants.ACC_PUBLIC;
import static com.v7878.dex.DexConstants.ACC_STATIC;
import static com.v7878.dex.builder.CodeBuilder.Op.GET_OBJECT;
import static com.v7878.unsafe.ArtModifiers.kAccCompileDontBother;
import static com.v7878.unsafe.ArtModifiers.kAccPreCompiled;
import static com.v7878.unsafe.ArtVersion.ART_SDK_INT;
import static com.v7878.unsafe.DexFileUtils.openDexFile;
import static com.v7878.unsafe.Reflection.fieldOffset;
import static com.v7878.unsafe.Reflection.getDeclaredField;
import static com.v7878.unsafe.Reflection.getDeclaredMethod;
import static com.v7878.unsafe.Reflection.unreflect;
import static com.v7878.unsafe.Utils.unsupportedSDK;
import static com.v7878.vmtools._Utils.rawMethodTypeOf;

import android.util.Pair;

import com.v7878.dex.DexIO;
import com.v7878.dex.builder.ClassBuilder;
import com.v7878.dex.immutable.ClassDef;
import com.v7878.dex.immutable.Dex;
import com.v7878.dex.immutable.FieldId;
import com.v7878.dex.immutable.MethodDef;
import com.v7878.dex.immutable.MethodId;
import com.v7878.dex.immutable.ProtoId;
import com.v7878.dex.immutable.TypeId;
import com.v7878.ti.JVMTI;
import com.v7878.unsafe.AndroidUnsafe;
import com.v7878.unsafe.ApiSensitive;
import com.v7878.unsafe.ArtMethodUtils;
import com.v7878.unsafe.ClassUtils;
import com.v7878.unsafe.ClassUtils.ClassStatus;
import com.v7878.unsafe.DexFileUtils;
import com.v7878.unsafe.invoke.Transformers;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TIHooks {
    private static final ClassLoader BOOT_CLASS_LOADER =
            ClassLoader.getSystemClassLoader().getParent();

    private static class ExecutableRedefinitionRequest {
        final Executable executable;
        final HookTransformer hooker;
        final int index;

        final MethodType static_type;
        final ProtoId static_proto;

        MethodDef def;

        private ExecutableRedefinitionRequest(
                Executable executable, HookTransformer hooker, int index) {
            this.executable = executable;
            this.hooker = hooker;
            this.index = index;
            this.static_type = rawMethodTypeOf(executable);
            this.static_proto = ProtoId.of(static_type);
        }

        public void setDef(ClassDef class_def) {
            // TODO: find direct/virtual
            def = class_def.findMethod(MethodId.of(executable));
        }

        public String handleName() {
            return "handle_" + index;
        }

        public String methodName() {
            return "executable_" + index;
        }
    }

    private static class ClassRedefinitionRequest {
        final Class<?> clazz;
        final List<ExecutableRedefinitionRequest> executables;

        ClassDef def;
        TypeId backup;

        private ClassRedefinitionRequest(Class<?> clazz) {
            this.executables = new ArrayList<>();
            this.clazz = clazz;
        }

        public void setDef(ClassDef def) {
            this.def = def;
            executables.forEach(ex -> ex.setDef(def));
        }

        public void setBackup(TypeId backup) {
            this.backup = backup;
        }
    }

    // TODO: move to ArtModifiers
    @ApiSensitive
    public static final int kAccSkipAccessChecks = switch (ART_SDK_INT) {
        case 36 /*android 16*/, 35 /*android 15*/, 34 /*android 14*/,
             33 /*android 13*/, 32 /*android 12L*/, 31 /*android 12*/,
             30 /*android 11*/, 29 /*android 10*/, 28 /*android 9*/,
             27 /*android 8.1*/, 26 /*android 8*/ -> 0x00080000;
        default -> throw unsupportedSDK(ART_SDK_INT);
    };

    public static void hook(Map<Executable, HookTransformer> hooks) {
        if (hooks.size() == 0) return;
        var requests = new HashMap<ClassLoader, Map<Class<?>, ClassRedefinitionRequest>>();
        int[] count = {0};
        hooks.forEach((executable, hooker) -> {
            if (executable instanceof Constructor<?>) {
                // TODO
                throw new IllegalArgumentException("Constructors are not supported");
            }
            if ((executable.getModifiers() & ACC_NATIVE) != 0) {
                // TODO
                throw new IllegalArgumentException("Native methods are not supported");
            }
            if ((executable.getModifiers() & ACC_ABSTRACT) != 0) {
                // TODO?
                throw new IllegalArgumentException("Abstract methods are not supported");
            }

            var clazz = executable.getDeclaringClass();
            var loader = clazz.getClassLoader();
            var requests_map = requests.computeIfAbsent(loader, unused -> new HashMap<>());
            var request = requests_map.computeIfAbsent(clazz, ClassRedefinitionRequest::new);
            request.executables.add(new ExecutableRedefinitionRequest(
                    executable, hooker, count[0]++));
        });

        Dex data = DexFileDump.readDex(requests.values().stream()
                .flatMap(map -> map.keySet().stream())
                .toArray(Class[]::new));
        for (var requests_map : requests.values()) {
            for (var entry : requests_map.entrySet()) {
                entry.getValue().setDef(data.findClass(TypeId.of(entry.getKey())));
            }
        }

        var mh_id = TypeId.of(MethodHandle.class);
        ProtoId mh_proto = ProtoId.of(TypeId.OBJECT, TypeId.OBJECT.array());
        MethodId mh_method = MethodId.of(mh_id, "invokeExact", mh_proto);

        for (var loader_entry : requests.entrySet()) {
            var loader = loader_entry.getKey();
            for (var request_entry : loader_entry.getValue().entrySet()) {
                var request = request_entry.getValue();

                var backup_name = _Utils.generateClassName(loader, "HookBackup");
                var backup_id = TypeId.ofName(backup_name);
                request.setBackup(backup_id);

                var backup_builder = ClassBuilder.newInstance();
                backup_builder.withFlags(ACC_PUBLIC | ACC_FINAL);
                backup_builder.withType(backup_id);
                var superclass = request.clazz.getSuperclass();
                if (superclass == null) {
                    // We hook java.lang.Object which has no superclass
                    backup_builder.withSuperClass(TypeId.OBJECT);
                } else {
                    ClassUtils.makeClassPublic(superclass);
                    // Required for invoke-super instruction to work correctly
                    backup_builder.withSuperClass(TypeId.of(superclass));
                }
                for (var executable : request.executables) {
                    var edef = executable.def;
                    assert (edef.getAccessFlags() & (ACC_NATIVE | ACC_ABSTRACT)) == 0;

                    backup_builder.withField(fb -> fb
                            .withFlags(ACC_PUBLIC | ACC_STATIC | ACC_FINAL)
                            .withName(executable.handleName())
                            .withType(mh_id)
                    );
                    backup_builder.withMethod(mb -> mb
                            .withFlags(ACC_PUBLIC | ACC_STATIC)
                            .withName(executable.methodName())
                            .withProto(executable.static_proto)
                            .withCode(edef.getImplementation())
                    );
                }
                var backup_def = backup_builder.finish();

                var dexfile = openDexFile(DexIO.write(Dex.of(backup_def)));
                DexFileUtils.setTrusted(dexfile);

                if (loader == BOOT_CLASS_LOADER) {
                    // TODO: move this check to DexFileUtils.loadClass
                    loader = null;
                }
                var backup = DexFileUtils.loadClass(dexfile, backup_name, loader);
                ClassUtils.setClassStatus(backup, ClassStatus.Verified);

                // TODO: simplify the search for methods and fields?
                for (var executable : request_entry.getValue().executables) {
                    var backup_method = getDeclaredMethod(backup, executable.methodName(),
                            executable.static_type.parameterArray());
                    ArtMethodUtils.changeExecutableFlags(backup_method, kAccPreCompiled,
                            kAccCompileDontBother | kAccSkipAccessChecks);
                    var backup_handle = unreflect(backup_method);
                    var handle_field = getDeclaredField(backup, executable.handleName());
                    var hooker_impl = new HookTransformerImpl(backup_handle, executable.hooker);
                    var hooker_handle = Transformers.makeTransformer(backup_handle.type(), hooker_impl);
                    AndroidUnsafe.putObject(backup, fieldOffset(handle_field), hooker_handle);
                }
            }
        }

        var redef_map = new ArrayList<Pair<Class<?>, byte[]>>(requests.size());

        for (var loader_entry : requests.entrySet()) {
            for (var request_entry : loader_entry.getValue().entrySet()) {
                var request = request_entry.getValue();
                var hook_builder = ClassBuilder.newInstance();
                hook_builder.of(request.def);

                for (var executable : request.executables) {
                    var proto = executable.static_proto;
                    var ret_shorty = proto.getReturnType().getShorty();
                    var ins = proto.countInputRegisters();

                    hook_builder.withMethod(mb -> mb
                            .of(executable.def)
                            .withCode(/* wide return */ 2, ib -> ib
                                    .sop(GET_OBJECT, ib.v(1), FieldId.of(
                                            request.backup, executable.handleName(), mh_id))
                                    .invoke_polymorphic_range(mh_method, proto,
                                            ins + /* handle */ 1, ib.v(1))
                                    .move_result_shorty(ret_shorty, ib.l(0))
                                    .return_shorty(ret_shorty, ib.l(0))
                            )
                    );
                }

                redef_map.add(new Pair<>(request_entry.getKey(),
                        DexIO.write(Dex.of(hook_builder.finish()))));
            }
        }

        //noinspection unchecked
        JVMTI.RedefineClasses(redef_map.toArray(new Pair[0]));
    }
}

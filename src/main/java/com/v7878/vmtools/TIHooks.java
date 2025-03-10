package com.v7878.vmtools;

import static com.v7878.dex.DexConstants.ACC_ABSTRACT;
import static com.v7878.dex.DexConstants.ACC_DIRECT_MASK;
import static com.v7878.dex.DexConstants.ACC_FINAL;
import static com.v7878.dex.DexConstants.ACC_NATIVE;
import static com.v7878.dex.DexConstants.ACC_PUBLIC;
import static com.v7878.dex.DexConstants.ACC_STATIC;
import static com.v7878.dex.DexConstants.ACC_VISIBILITY_MASK;
import static com.v7878.dex.Opcode.IGET;
import static com.v7878.dex.Opcode.IGET_BOOLEAN;
import static com.v7878.dex.Opcode.IGET_BYTE;
import static com.v7878.dex.Opcode.IGET_CHAR;
import static com.v7878.dex.Opcode.IGET_OBJECT;
import static com.v7878.dex.Opcode.IGET_SHORT;
import static com.v7878.dex.Opcode.IGET_WIDE;
import static com.v7878.dex.Opcode.IPUT;
import static com.v7878.dex.Opcode.IPUT_BOOLEAN;
import static com.v7878.dex.Opcode.IPUT_BYTE;
import static com.v7878.dex.Opcode.IPUT_CHAR;
import static com.v7878.dex.Opcode.IPUT_OBJECT;
import static com.v7878.dex.Opcode.IPUT_SHORT;
import static com.v7878.dex.Opcode.IPUT_WIDE;
import static com.v7878.dex.builder.CodeBuilder.Op.GET_OBJECT;
import static com.v7878.unsafe.ArtModifiers.kAccCompileDontBother;
import static com.v7878.unsafe.ArtModifiers.kAccPreCompiled;
import static com.v7878.unsafe.ArtModifiers.kAccSkipAccessChecks;
import static com.v7878.unsafe.ArtVersion.ART_SDK_INT;
import static com.v7878.unsafe.DexFileUtils.openDexFile;
import static com.v7878.unsafe.Reflection.fieldOffset;
import static com.v7878.unsafe.Reflection.getArtMethods;
import static com.v7878.unsafe.Reflection.getDeclaredField;
import static com.v7878.unsafe.Reflection.getDeclaredMethod;
import static com.v7878.unsafe.Reflection.getHiddenField;
import static com.v7878.unsafe.Reflection.getHiddenFields;
import static com.v7878.unsafe.Reflection.unreflect;
import static com.v7878.unsafe.Utils.check;
import static com.v7878.unsafe.Utils.shouldNotReachHere;
import static com.v7878.unsafe.foreign.BulkLinker.CallSignature;
import static com.v7878.unsafe.foreign.BulkLinker.CallType.CRITICAL;
import static com.v7878.unsafe.foreign.BulkLinker.LibrarySymbol;
import static com.v7878.unsafe.foreign.BulkLinker.MapType.INT;
import static com.v7878.unsafe.foreign.BulkLinker.MapType.LONG_AS_WORD;
import static com.v7878.unsafe.foreign.BulkLinker.MapType.SHORT;
import static com.v7878.unsafe.foreign.BulkLinker.processSymbols;
import static com.v7878.unsafe.foreign.LibArt.ART;
import static com.v7878.vmtools._Utils.rawMethodTypeOf;

import android.util.Pair;

import com.v7878.dex.DexIO;
import com.v7878.dex.DexIO.DexReaderCache;
import com.v7878.dex.Opcode;
import com.v7878.dex.builder.ClassBuilder;
import com.v7878.dex.builder.MethodBuilder;
import com.v7878.dex.immutable.ClassDef;
import com.v7878.dex.immutable.Dex;
import com.v7878.dex.immutable.FieldId;
import com.v7878.dex.immutable.MethodDef;
import com.v7878.dex.immutable.MethodId;
import com.v7878.dex.immutable.MethodImplementation;
import com.v7878.dex.immutable.ProtoId;
import com.v7878.dex.immutable.TypeId;
import com.v7878.dex.immutable.bytecode.Instruction10x;
import com.v7878.dex.immutable.bytecode.Instruction22c22cs;
import com.v7878.dex.immutable.bytecode.Instruction35c35mi35ms;
import com.v7878.dex.immutable.bytecode.Instruction3rc3rmi3rms;
import com.v7878.foreign.Arena;
import com.v7878.r8.annotations.DoNotOptimize;
import com.v7878.r8.annotations.DoNotShrink;
import com.v7878.r8.annotations.DoNotShrinkType;
import com.v7878.ti.JVMTI;
import com.v7878.unsafe.AndroidUnsafe;
import com.v7878.unsafe.ArtFieldUtils;
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
import java.util.Objects;

public class TIHooks {
    private static boolean needsDequicken() {
        // Below API 28 there is another verification method,
        //  and above API 30 there are no "quick" opcodes
        return ART_SDK_INT >= 28 && ART_SDK_INT <= 30;
    }

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
            var id = MethodId.of(executable);
            MethodDef edef;
            if ((ArtMethodUtils.getExecutableFlags(executable) & ACC_DIRECT_MASK) != 0) {
                edef = class_def.findDirectMethod(id);
            } else {
                edef = class_def.findVirtualMethod(id);
            }
            this.def = Objects.requireNonNull(edef);
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

        public void setDef(Pair<ClassDef, DexReaderCache> pair) {
            this.def = needsDequicken() ? dequicken(pair.second, clazz, pair.first) : pair.first;
            this.executables.forEach(ex -> ex.setDef(this.def));
        }

        public void setBackup(TypeId backup) {
            this.backup = backup;
        }
    }

    @DoNotShrinkType
    @DoNotOptimize
    @SuppressWarnings("SameParameterValue")
    private abstract static class Native {
        @DoNotShrink
        private static final Arena SCOPE = Arena.ofAuto();

        @LibrarySymbol(name = "_ZN3art9ArtMethod22GetIndexFromQuickeningEj")
        @CallSignature(type = CRITICAL, ret = SHORT, args = {LONG_AS_WORD, INT})
        abstract short GetIndexFromQuickening(long thiz, int dex_pc);

        static final Native INSTANCE = AndroidUnsafe.allocateInstance(
                processSymbols(SCOPE, Native.class, ART));
    }

    private static int getQuickId(long art_method, int dex_pc) {
        int idx = Native.INSTANCE.GetIndexFromQuickening(art_method, dex_pc) & 0xffff;
        check(idx != 0xffff, AssertionError::new);
        return idx;
    }

    private static MethodId getMethodId(DexReaderCache cache, long art_method, int dex_pc) {
        return cache.getMethodId(getQuickId(art_method, dex_pc));
    }

    private static FieldId getFieldId(DexReaderCache cache, long art_method, int dex_pc) {
        return cache.getFieldId(getQuickId(art_method, dex_pc));
    }

    private static MethodImplementation dequicken(
            DexReaderCache cache, long art_method, MethodImplementation impl) {
        var insns = new ArrayList<>(impl.getInstructions());
        boolean modified = false;
        int pc = 0;
        for (int i = 0; i < insns.size(); i++) {
            var insn = insns.get(i);
            switch (insn.getOpcode()) {
                case INVOKE_VIRTUAL_QUICK -> {
                    var tmp = (Instruction35c35mi35ms) insn;
                    var mid = getMethodId(cache, art_method, pc);
                    insns.set(i, Instruction35c35mi35ms.of(
                            Opcode.INVOKE_VIRTUAL,
                            tmp.getRegisterCount(),
                            tmp.getRegister1(),
                            tmp.getRegister2(),
                            tmp.getRegister3(),
                            tmp.getRegister4(),
                            tmp.getRegister5(),
                            mid
                    ));
                    modified = true;
                }
                case INVOKE_VIRTUAL_QUICK_RANGE -> {
                    var tmp = (Instruction3rc3rmi3rms) insn;
                    var mid = getMethodId(cache, art_method, pc);
                    insns.set(i, Instruction3rc3rmi3rms.of(
                            Opcode.INVOKE_VIRTUAL_RANGE,
                            tmp.getRegisterCount(),
                            tmp.getStartRegister(),
                            mid
                    ));
                    modified = true;
                }
                case IGET_BOOLEAN_QUICK,
                     IGET_BYTE_QUICK,
                     IGET_CHAR_QUICK,
                     IGET_SHORT_QUICK,
                     IGET_QUICK,
                     IGET_WIDE_QUICK,
                     IGET_OBJECT_QUICK,

                     IPUT_BOOLEAN_QUICK,
                     IPUT_BYTE_QUICK,
                     IPUT_CHAR_QUICK,
                     IPUT_SHORT_QUICK,
                     IPUT_QUICK,
                     IPUT_WIDE_QUICK,
                     IPUT_OBJECT_QUICK -> {
                    var tmp = (Instruction22c22cs) insn;
                    var fid = getFieldId(cache, art_method, pc);
                    insns.set(i, Instruction22c22cs.of(
                            switch (insn.getOpcode()) {
                                case IGET_BOOLEAN_QUICK -> IGET_BOOLEAN;
                                case IGET_BYTE_QUICK -> IGET_BYTE;
                                case IGET_CHAR_QUICK -> IGET_CHAR;
                                case IGET_SHORT_QUICK -> IGET_SHORT;
                                case IGET_QUICK -> IGET;
                                case IGET_WIDE_QUICK -> IGET_WIDE;
                                case IGET_OBJECT_QUICK -> IGET_OBJECT;

                                case IPUT_BOOLEAN_QUICK -> IPUT_BOOLEAN;
                                case IPUT_BYTE_QUICK -> IPUT_BYTE;
                                case IPUT_CHAR_QUICK -> IPUT_CHAR;
                                case IPUT_SHORT_QUICK -> IPUT_SHORT;
                                case IPUT_QUICK -> IPUT;
                                case IPUT_WIDE_QUICK -> IPUT_WIDE;
                                case IPUT_OBJECT_QUICK -> IPUT_OBJECT;

                                default -> throw shouldNotReachHere();
                            },
                            tmp.getRegister1(),
                            tmp.getRegister2(),
                            fid
                    ));
                    modified = true;
                }
                case RETURN_VOID_NO_BARRIER -> {
                    insns.set(i, Instruction10x.of(Opcode.RETURN_VOID));
                    modified = true;
                }
            }
            assert !insns.get(i).getOpcode().odexOnly();
            pc += insn.getUnitCount();
        }
        if (modified) {
            return MethodImplementation.of(impl.getRegisterCount(),
                    insns, impl.getTryBlocks(), impl.getDebugItems());
        }
        return impl;
    }

    private static ClassDef dequicken(
            DexReaderCache cache, Class<?> clazz, ClassDef cdef) {
        var art_methods = getArtMethods(clazz);
        var old_methods = cdef.getMethods();
        assert art_methods.length == old_methods.size();
        var new_methods = new ArrayList<MethodDef>(old_methods.size());
        int i = 0;
        for (var edef : old_methods) {
            var art_method = art_methods[i];
            var impl = edef.getImplementation();
            new_methods.add(impl == null ? edef : MethodBuilder.build(mb -> mb
                    .of(edef)
                    .withCode(dequicken(cache, art_method, impl))
            ));
            i++;
        }
        return ClassBuilder.build(cb -> cb.of(cdef).setMethods(new_methods));
    }

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

        var data = DexFileDump.readWithCache(requests.values().stream()
                .flatMap(map -> map.keySet().stream())
                .toArray(Class[]::new));
        for (var requests_map : requests.values()) {
            for (var entry : requests_map.entrySet()) {
                var type = TypeId.of(entry.getKey());
                entry.getValue().setDef(data.stream()
                        .filter(pair -> type.equals(pair.first.getType()))
                        .findAny().orElseThrow());
            }
        }

        var mh_id = TypeId.of(MethodHandle.class);
        ProtoId mh_proto = ProtoId.of(TypeId.OBJECT, TypeId.OBJECT.array());
        MethodId mh_method = MethodId.of(mh_id, "invokeExact", mh_proto);

        for (var loader_entry : requests.entrySet()) {
            var loader = loader_entry.getKey();
            for (var request_entry : loader_entry.getValue().entrySet()) {
                var request = request_entry.getValue();

                if (ART_SDK_INT <= 28) {
                    // Up to android 9 inclusive, marking backup methods as kAccSkipAccessChecks is not enough
                    var fields = getHiddenFields(request.clazz);
                    for (var field : fields) {
                        ArtFieldUtils.changeFieldFlags(field, ACC_VISIBILITY_MASK, ACC_PUBLIC);
                    }
                }

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

        var methods_map = new HashMap<Long, Integer>(requests.size());
        class Holder {
            static final long METHODS_OFFSET = fieldOffset(
                    getHiddenField(Class.class, "methods"));
            static final long COPIED_METHODS_OFFSET = fieldOffset(
                    getHiddenField(Class.class, "copiedMethodsOffset"));
        }

        for (var loader_entry : requests.entrySet()) {
            for (var request_entry : loader_entry.getValue().entrySet()) {
                var request = request_entry.getValue();

                // Fix bug in API 26 - when redefining a class,
                //  iteration occurs not only on the methods that the class declares,
                //  but also on the methods of superinterfaces
                if (ART_SDK_INT == 26) {
                    long methods = AndroidUnsafe.getLongO(
                            request.clazz, Holder.METHODS_OFFSET);
                    assert methods != 0;
                    int methods_count = AndroidUnsafe.getIntN(methods);
                    assert methods_count != 0;
                    int copied = AndroidUnsafe.getShortO(request.clazz,
                            Holder.COPIED_METHODS_OFFSET) & 0xffff;
                    assert methods_count >= copied;
                    AndroidUnsafe.putIntN(methods, copied);
                    methods_map.put(methods, methods_count);
                }

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

        if (ART_SDK_INT == 26) {
            methods_map.forEach(AndroidUnsafe::putIntN);
        }
    }
}

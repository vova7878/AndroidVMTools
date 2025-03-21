package com.v7878.vmtools;

import com.v7878.unsafe.invoke.EmulatedStackFrame;
import com.v7878.unsafe.invoke.Transformers.AbstractTransformer;

import java.lang.invoke.MethodHandle;

public interface HookTransformer {
    void transform(MethodHandle original, EmulatedStackFrame stack) throws Throwable;
}

final class HookTransformerImpl extends AbstractTransformer {
    private final MethodHandle original;
    private final HookTransformer transformer;

    HookTransformerImpl(MethodHandle original, HookTransformer transformer) {
        this.original = original;
        this.transformer = transformer;
    }

    @Override
    protected void transform(MethodHandle thiz, EmulatedStackFrame stack) throws Throwable {
        stack.type(original.type());
        transformer.transform(original, stack);
    }
}

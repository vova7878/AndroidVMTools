package com.v7878.vmtools;

public class JVMTIException extends RuntimeException {
    public JVMTIException(int error) {
        super(JVMTI.GetErrorName(error));
    }
}

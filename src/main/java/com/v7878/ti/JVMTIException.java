package com.v7878.ti;

public class JVMTIException extends RuntimeException {
    public JVMTIException(int error) {
        super(JVMTI.GetErrorName(error));
    }
}

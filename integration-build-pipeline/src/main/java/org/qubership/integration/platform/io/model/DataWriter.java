package org.qubership.integration.platform.io.model;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.OutputStream;

public interface DataWriter<T> {
    void write(@NotNull OutputStream stream, @NotNull T data) throws IOException;
}

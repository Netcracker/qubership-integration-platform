package org.qubership.integration.platform.io.model;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;

public interface DataReader<T> {
    @NotNull
    T read(@NotNull InputStream stream) throws IOException;
}

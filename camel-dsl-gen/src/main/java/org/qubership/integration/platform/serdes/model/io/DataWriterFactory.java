package org.qubership.integration.platform.serdes.model.io;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;

public interface DataWriterFactory<T> {
    @NotNull
    Optional<? extends DataWriter<T>> getWriter(@NotNull DataFormat format);
}

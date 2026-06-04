package org.qubership.integration.platform.serdes.model.io;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;

public interface DataReaderFactory<T> {
    @NotNull
    Optional<? extends DataReader<T>> getReader(@NotNull DataFormat format);
}

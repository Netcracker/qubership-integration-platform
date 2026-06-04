package org.qubership.integration.platform.serdes.impl.io;

import org.jetbrains.annotations.NotNull;
import org.qubership.integration.platform.serdes.model.io.DataFormat;
import org.qubership.integration.platform.serdes.model.io.DataFormatAwareWriter;
import org.qubership.integration.platform.serdes.model.io.DataWriter;
import org.qubership.integration.platform.serdes.model.io.DataWriterFactory;

import java.util.Collection;
import java.util.Optional;

public class DataWriterFactoryImpl<T> implements DataWriterFactory<T> {
    private final Collection<DataFormatAwareWriter<T>> writers;

    public DataWriterFactoryImpl(@NotNull Collection<DataFormatAwareWriter<T>> writers) {
        this.writers = writers;
    }

    @Override
    public @NotNull Optional<? extends DataWriter<T>> getWriter(@NotNull DataFormat format) {
        return writers.stream()
            .filter(writer -> format.equals(writer.getDataFormat()))
            .findAny();
    }
}

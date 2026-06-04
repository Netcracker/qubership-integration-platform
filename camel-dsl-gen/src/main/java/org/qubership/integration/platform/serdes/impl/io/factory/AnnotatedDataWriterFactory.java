package org.qubership.integration.platform.serdes.impl.io.factory;

import org.jetbrains.annotations.NotNull;
import org.qubership.integration.platform.serdes.model.io.DataFormat;
import org.qubership.integration.platform.serdes.model.io.DataWriter;
import org.qubership.integration.platform.serdes.model.io.DataWriterFactory;

import java.util.Collection;
import java.util.Optional;

public class AnnotatedDataWriterFactory<T> implements DataWriterFactory<T> {
    private final Collection<DataWriter<T>> writers;

    public AnnotatedDataWriterFactory(@NotNull Collection<DataWriter<T>> writers) {
        this.writers = writers;
    }

    @Override
    public @NotNull Optional<? extends DataWriter<T>> getWriter(@NotNull DataFormat format) {
        return DataFormatHelper.findObjectForDataFormat(writers, format);
    }
}

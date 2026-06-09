package org.qubership.integration.platform.io.factories;

import org.jetbrains.annotations.NotNull;
import org.qubership.integration.platform.io.model.DataFormat;
import org.qubership.integration.platform.io.model.DataWriter;
import org.qubership.integration.platform.io.model.DataWriterFactory;

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

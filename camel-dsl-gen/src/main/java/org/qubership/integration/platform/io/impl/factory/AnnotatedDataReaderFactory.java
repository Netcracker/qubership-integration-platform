package org.qubership.integration.platform.io.impl.factory;

import org.jetbrains.annotations.NotNull;
import org.qubership.integration.platform.io.model.DataFormat;
import org.qubership.integration.platform.io.model.DataReader;
import org.qubership.integration.platform.io.model.DataReaderFactory;

import java.util.Collection;
import java.util.Optional;

public class AnnotatedDataReaderFactory<T> implements DataReaderFactory<T> {
    private final Collection<DataReader<T>> readers;

    public AnnotatedDataReaderFactory(Collection<DataReader<T>> readers) {
        this.readers = readers;
    }

    @Override
    public @NotNull Optional<? extends DataReader<T>> getReader(@NotNull DataFormat format) {
        return DataFormatHelper.findObjectForDataFormat(readers, format);
    }
}

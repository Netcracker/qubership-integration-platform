package org.qubership.integration.platform.serdes.impl.io;

import org.jetbrains.annotations.NotNull;
import org.qubership.integration.platform.serdes.model.io.DataFormat;
import org.qubership.integration.platform.serdes.model.io.DataFormatAwareReader;
import org.qubership.integration.platform.serdes.model.io.DataReader;
import org.qubership.integration.platform.serdes.model.io.DataReaderFactory;

import java.util.Collection;
import java.util.Optional;

public class DataReaderFactoryImpl<T> implements DataReaderFactory<T> {
    private final Collection<DataFormatAwareReader<T>> readers;

    public DataReaderFactoryImpl(Collection<DataFormatAwareReader<T>> readers) {
        this.readers = readers;
    }

    @Override
    public @NotNull Optional<? extends DataReader<T>> getReader(@NotNull DataFormat format) {
        return readers.stream()
            .filter(reader -> format.equals(reader.getDataFormat()))
            .findAny();
    }
}

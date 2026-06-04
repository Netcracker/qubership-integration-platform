package org.qubership.integration.platform.serdes.impl.io;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.jetbrains.annotations.NotNull;
import org.qubership.integration.platform.serdes.model.data.Chain;
import org.qubership.integration.platform.serdes.model.io.DataFormatAwareWriter;

import java.util.List;

@Named
@ApplicationScoped
public class ChainWriterFactory extends DataWriterFactoryImpl<Chain> {
    @Inject
    public ChainWriterFactory(@NotNull List<DataFormatAwareWriter<Chain>> writers) {
        super(writers);
    }
}

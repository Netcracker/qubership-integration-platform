package org.qubership.integration.platform.serdes.impl.io;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.jetbrains.annotations.NotNull;
import org.qubership.integration.platform.serdes.impl.io.factory.AnnotatedDataWriterFactory;
import org.qubership.integration.platform.serdes.model.data.Chain;
import org.qubership.integration.platform.serdes.model.io.DataWriter;

import java.util.List;

@Named
@ApplicationScoped
public class ChainWriterFactory extends AnnotatedDataWriterFactory<Chain> {
    @Inject
    public ChainWriterFactory(@NotNull List<DataWriter<Chain>> writers) {
        super(writers);
    }
}

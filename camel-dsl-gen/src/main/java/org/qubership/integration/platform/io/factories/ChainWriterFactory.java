package org.qubership.integration.platform.io.factories;

import org.jetbrains.annotations.NotNull;
import org.qubership.integration.platform.chain.model.Chain;
import org.qubership.integration.platform.io.model.DataWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ChainWriterFactory extends AnnotatedDataWriterFactory<Chain> {
    @Autowired
    public ChainWriterFactory(@NotNull List<DataWriter<Chain>> writers) {
        super(writers);
    }
}

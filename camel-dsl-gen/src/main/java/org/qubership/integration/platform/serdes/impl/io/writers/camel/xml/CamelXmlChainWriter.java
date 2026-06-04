package org.qubership.integration.platform.serdes.impl.io.writers.camel.xml;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import org.jetbrains.annotations.NotNull;
import org.qubership.integration.platform.serdes.model.data.Chain;
import org.qubership.integration.platform.serdes.model.io.DataFormat;
import org.qubership.integration.platform.serdes.model.io.DataFormatAwareWriter;

import java.io.IOException;
import java.io.OutputStream;

@Named
@ApplicationScoped
public class CamelXmlChainWriter implements DataFormatAwareWriter<Chain> {
    @Override
    public @NotNull DataFormat getDataFormat() {
        return DataFormat.CAMEL_XML;
    }

    @Override
    public void write(@NotNull OutputStream stream, @NotNull Chain data) throws IOException {
        throw new RuntimeException("Not implemented yet");
    }
}

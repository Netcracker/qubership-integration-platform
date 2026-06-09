package org.qubership.integration.platform.io.impl.writers.camel.xml;

import org.jetbrains.annotations.NotNull;
import org.qubership.integration.platform.chain.model.Chain;
import org.qubership.integration.platform.io.impl.factory.ForDataFormat;
import org.qubership.integration.platform.io.model.DataFormat;
import org.qubership.integration.platform.io.model.DataWriter;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;

@Component
@ForDataFormat(DataFormat.CAMEL_XML)
public class CamelXmlChainWriter implements DataWriter<Chain> {
    @Override
    public void write(@NotNull OutputStream stream, @NotNull Chain data) throws IOException {
        throw new RuntimeException("Not implemented yet");
    }
}

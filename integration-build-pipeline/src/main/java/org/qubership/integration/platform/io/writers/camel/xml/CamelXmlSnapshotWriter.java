package org.qubership.integration.platform.io.writers.camel.xml;

import org.jetbrains.annotations.NotNull;
import org.qubership.integration.platform.chain.model.Snapshot;
import org.qubership.integration.platform.io.factories.ForDataFormat;
import org.qubership.integration.platform.io.model.DataFormat;
import org.qubership.integration.platform.io.model.DataWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;

@Component
@ForDataFormat(DataFormat.CAMEL_XML)
public class CamelXmlSnapshotWriter implements DataWriter<Snapshot> {
    private final XmlBuilder xmlBuilder;

    @Autowired
    public CamelXmlSnapshotWriter(XmlBuilder xmlBuilder) {
        this.xmlBuilder = xmlBuilder;
    }

    @Override
    public void write(@NotNull OutputStream stream, @NotNull Snapshot data) throws IOException {
        try {
            String content = xmlBuilder.build(data.getElements());
            stream.write(content.getBytes());
        } catch (IOException ioException) {
            throw ioException;
        } catch (Exception e) {
            throw new IOException("Failed to write data: " + e.getMessage(), e);
        }
    }
}

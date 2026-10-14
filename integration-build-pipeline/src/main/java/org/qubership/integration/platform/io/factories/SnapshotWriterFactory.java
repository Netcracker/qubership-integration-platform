package org.qubership.integration.platform.io.factories;

import org.jetbrains.annotations.NotNull;
import org.qubership.integration.platform.chain.model.Snapshot;
import org.qubership.integration.platform.io.model.DataWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SnapshotWriterFactory extends AnnotatedDataWriterFactory<Snapshot> {
    @Autowired
    public SnapshotWriterFactory(@NotNull List<DataWriter<Snapshot>> writers) {
        super(writers);
    }
}

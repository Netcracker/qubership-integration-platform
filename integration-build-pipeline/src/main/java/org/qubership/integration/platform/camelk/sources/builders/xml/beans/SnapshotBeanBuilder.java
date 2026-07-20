package org.qubership.integration.platform.camelk.sources.builders.xml.beans;

import org.codehaus.stax2.XMLStreamWriter2;
import org.qubership.integration.platform.camelk.sources.SourceBuilderContext;
import org.qubership.integration.platform.chain.model.Snapshot;

public interface SnapshotBeanBuilder {
    void build(
            XMLStreamWriter2 streamWriter,
            Snapshot snapshot,
            SourceBuilderContext context
    ) throws Exception;
}

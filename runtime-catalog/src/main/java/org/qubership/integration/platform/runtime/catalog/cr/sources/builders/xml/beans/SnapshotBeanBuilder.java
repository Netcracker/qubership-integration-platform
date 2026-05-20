package org.qubership.integration.platform.runtime.catalog.cr.sources.builders.xml.beans;

import org.codehaus.stax2.XMLStreamWriter2;
import org.qubership.integration.platform.runtime.catalog.cr.sources.SourceBuilderContext;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;

public interface SnapshotBeanBuilder {
    void build(
            XMLStreamWriter2 streamWriter,
            Snapshot snapshot,
            SourceBuilderContext context
    ) throws Exception;
}

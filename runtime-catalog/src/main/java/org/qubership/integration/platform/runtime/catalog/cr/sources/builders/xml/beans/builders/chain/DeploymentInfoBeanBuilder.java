package org.qubership.integration.platform.runtime.catalog.cr.sources.builders.xml.beans.builders.chain;

import org.codehaus.stax2.XMLStreamWriter2;
import org.qubership.integration.platform.runtime.catalog.cr.sources.SourceBuilderContext;
import org.qubership.integration.platform.runtime.catalog.cr.sources.builders.xml.beans.SnapshotBeanBuilder;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.springframework.stereotype.Component;

@Component
public class DeploymentInfoBeanBuilder implements SnapshotBeanBuilder {
    @Override
    public void build(
            XMLStreamWriter2 streamWriter,
            Snapshot snapshot,
            SourceBuilderContext context
    ) throws Exception {
        streamWriter.writeStartElement("bean");
        streamWriter.writeAttribute("name", "DeploymentInfo-" + snapshot.getId());
        streamWriter.writeAttribute("type", "org.qubership.integration.platform.engine.metadata.DeploymentInfo");

        streamWriter.writeStartElement("properties");

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "id");
        streamWriter.writeAttribute("value", String.format("%s-%s", context.getDomainName(), snapshot.getId()));

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "name");
        streamWriter.writeAttribute("value", context.getBuildName());

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "timestamp");
        streamWriter.writeAttribute("value", Long.toString(context.getBuildTimestamp().getEpochSecond()));

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "chain.id");
        streamWriter.writeAttribute("value", snapshot.getChain().getId());

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "chain.name");
        streamWriter.writeAttribute("value", snapshot.getChain().getName());

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "snapshot.id");
        streamWriter.writeAttribute("value", snapshot.getId());

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "snapshot.name");
        streamWriter.writeAttribute("value", snapshot.getName());

        streamWriter.writeEndElement();
        streamWriter.writeEndElement();
    }
}

package org.qubership.integration.platform.runtime.catalog.cr.sources.builders.xml.beans.builders.chain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.codehaus.stax2.XMLStreamWriter2;
import org.qubership.integration.platform.runtime.catalog.cr.sources.SourceBuilderContext;
import org.qubership.integration.platform.runtime.catalog.cr.sources.builders.xml.beans.SnapshotBeanBuilder;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.MaskedField;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

@Component
public class MaskedFieldsBeanBuilder implements SnapshotBeanBuilder {
    private final ObjectMapper objectMapper;

    @Autowired
    public MaskedFieldsBeanBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void build(
            XMLStreamWriter2 streamWriter,
            Snapshot snapshot,
            SourceBuilderContext context
    ) throws Exception {
        streamWriter.writeStartElement("bean");
        streamWriter.writeAttribute("name", "MaskedFields-" + snapshot.getId());
        streamWriter.writeAttribute("type", "org.qubership.integration.platform.engine.metadata.MaskedFields");
        streamWriter.writeAttribute("factoryMethod", "fromJsonString");

        streamWriter.writeStartElement("constructors");

        streamWriter.writeEmptyElement("constructor");
        streamWriter.writeAttribute("index", "0");
        streamWriter.writeAttribute("value", getMaskedFieldsValue(snapshot));

        streamWriter.writeEndElement();
        streamWriter.writeEndElement();
    }

    private String getMaskedFieldsValue(Snapshot snapshot) throws Exception {
        Set<String> fields = snapshot.getMaskedFields().stream()
                .map(MaskedField::getName)
                .collect(Collectors.toSet());
        return objectMapper.writeValueAsString(fields);
    }
}

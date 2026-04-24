package org.qubership.integration.platform.runtime.catalog.cr.sources.builders.xml.beans.builders.element;

import org.codehaus.stax2.XMLStreamWriter2;
import org.qubership.integration.platform.runtime.catalog.builder.BuilderConstants;
import org.qubership.integration.platform.runtime.catalog.cr.sources.SourceBuilderContext;
import org.qubership.integration.platform.runtime.catalog.cr.sources.builders.xml.beans.ElementBeansBuilder;
import org.qubership.integration.platform.runtime.catalog.model.library.ElementType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.service.ElementService;
import org.qubership.integration.platform.runtime.catalog.service.library.LibraryElementsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static java.util.Objects.nonNull;
import static org.qubership.integration.platform.runtime.catalog.consul.ConfigurationPropertiesConstants.ELEMENTS_WITH_INTERMEDIATE_CHILDREN;

@Component
public class CommonBeansBuilder implements ElementBeansBuilder {
    private final LibraryElementsService libraryService;

    @Autowired
    public CommonBeansBuilder(LibraryElementsService libraryService) {
        this.libraryService = libraryService;
    }

    @Override
    public boolean applicableTo(ChainElement element) {
        return true;
    }

    @Override
    public void build(
            XMLStreamWriter2 streamWriter,
            ChainElement element,
            SourceBuilderContext context
    ) throws Exception {
        streamWriter.writeStartElement("bean");
        streamWriter.writeAttribute("name", "ElementInfo-" + element.getId());
        streamWriter.writeAttribute("type", "org.qubership.integration.platform.engine.metadata.ElementInfo");

        streamWriter.writeStartElement("properties");

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "id");
        streamWriter.writeAttribute("value", element.getOriginalId());

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "name");
        streamWriter.writeAttribute("value", element.getName());

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "type");
        streamWriter.writeAttribute("value", element.getType());

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "chainId");
        streamWriter.writeAttribute("value", element.getSnapshot().getChain().getId());

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "snapshotId");
        streamWriter.writeAttribute("value", element.getSnapshot().getId());

        if (nonNull(element.getParent())) {
            ChainElement parent = element.getParent();
            if (ElementService.CONTAINER_TYPE_NAME.equals(parent.getType())
                || Optional.ofNullable(libraryService.getElementDescriptor(parent.getType()))
                    .map(descriptor -> ElementType.REUSE == descriptor.getType())
                    .orElse(false)) {
                streamWriter.writeEmptyElement("property");
                streamWriter.writeAttribute("key", "parentId");
                streamWriter.writeAttribute("value", element.getParent().getOriginalId());

                streamWriter.writeEmptyElement("property");
                streamWriter.writeAttribute("key", "hasIntermediateParents");
                streamWriter.writeAttribute("value",
                        Boolean.toString(ELEMENTS_WITH_INTERMEDIATE_CHILDREN
                                .contains(element.getParent().getType())));

            }

            if (BuilderConstants.REUSE_ELEMENT_TYPE.equals(element.getParent().getType())) {
                streamWriter.writeEmptyElement("property");
                streamWriter.writeAttribute("key", "reuseId");
                streamWriter.writeAttribute("value", element.getParent().getOriginalId());
            }
        }

        streamWriter.writeEndElement();
        streamWriter.writeEndElement();
    }
}

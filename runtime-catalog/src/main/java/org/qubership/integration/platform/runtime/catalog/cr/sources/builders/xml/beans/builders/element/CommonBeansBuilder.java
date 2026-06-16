package org.qubership.integration.platform.runtime.catalog.cr.sources.builders.xml.beans.builders.element;

import org.codehaus.stax2.XMLStreamWriter2;
import org.qubership.integration.platform.library.components.LibraryElementsService;
import org.qubership.integration.platform.library.model.ElementDescriptor;
import org.qubership.integration.platform.library.model.ElementType;
import org.qubership.integration.platform.io.writers.camel.xml.BuilderConstants;
import org.qubership.integration.platform.runtime.catalog.cr.sources.SourceBuilderContext;
import org.qubership.integration.platform.runtime.catalog.cr.sources.builders.xml.beans.ElementBeansBuilder;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.service.ElementService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static java.util.Objects.nonNull;
import static org.qubership.integration.platform.library.constants.ConfigurationPropertiesConstants.ELEMENTS_WITH_INTERMEDIATE_CHILDREN;
import static org.qubership.integration.platform.runtime.catalog.cr.sources.builders.xml.beans.XmlBeanConstants.*;

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

        streamWriter.writeEmptyElement(XML_PROPERTY);
        streamWriter.writeAttribute(ATTR_KEY, "id");
        streamWriter.writeAttribute(ATTR_VALUE, element.getOriginalId());

        streamWriter.writeEmptyElement(XML_PROPERTY);
        streamWriter.writeAttribute(ATTR_KEY, "snapshotElementId");
        streamWriter.writeAttribute(ATTR_VALUE, element.getId());

        streamWriter.writeEmptyElement(XML_PROPERTY);
        streamWriter.writeAttribute(ATTR_KEY, "name");
        streamWriter.writeAttribute(ATTR_VALUE, element.getName());

        streamWriter.writeEmptyElement(XML_PROPERTY);
        streamWriter.writeAttribute(ATTR_KEY, "type");
        streamWriter.writeAttribute(ATTR_VALUE, element.getType());

        streamWriter.writeEmptyElement(XML_PROPERTY);
        streamWriter.writeAttribute(ATTR_KEY, "chainId");
        streamWriter.writeAttribute(ATTR_VALUE, element.getSnapshot().getChain().getId());

        streamWriter.writeEmptyElement(XML_PROPERTY);
        streamWriter.writeAttribute(ATTR_KEY, "snapshotId");
        streamWriter.writeAttribute(ATTR_VALUE, element.getSnapshot().getId());

        if (nonNull(element.getParent())) {
            ChainElement parent = element.getParent();
            if (ElementService.CONTAINER_TYPE_NAME.equals(parent.getType())
                || libraryService.lookupElementDescriptor(parent.getType())
                    .map(ElementDescriptor::getType)
                    .map(ElementType.REUSE::equals)
                    .orElse(false)) {
                streamWriter.writeEmptyElement(XML_PROPERTY);
                streamWriter.writeAttribute(ATTR_KEY, "parentId");
                streamWriter.writeAttribute(ATTR_VALUE, element.getParent().getOriginalId());

                streamWriter.writeEmptyElement(XML_PROPERTY);
                streamWriter.writeAttribute(ATTR_KEY, "hasIntermediateParents");
                streamWriter.writeAttribute(ATTR_VALUE,
                        Boolean.toString(ELEMENTS_WITH_INTERMEDIATE_CHILDREN
                                .contains(element.getParent().getType())));

            }

            if (BuilderConstants.REUSE_ELEMENT_TYPE.equals(element.getParent().getType())) {
                streamWriter.writeEmptyElement(XML_PROPERTY);
                streamWriter.writeAttribute(ATTR_KEY, "reuseId");
                streamWriter.writeAttribute(ATTR_VALUE, element.getParent().getOriginalId());
            }
        }

        streamWriter.writeEndElement();
        streamWriter.writeEndElement();
    }
}

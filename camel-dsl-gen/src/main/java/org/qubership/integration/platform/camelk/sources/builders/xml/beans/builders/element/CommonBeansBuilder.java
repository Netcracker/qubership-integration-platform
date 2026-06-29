package org.qubership.integration.platform.camelk.sources.builders.xml.beans.builders.element;

import org.codehaus.stax2.XMLStreamWriter2;
import org.qubership.integration.platform.camelk.sources.SourceBuilderContext;
import org.qubership.integration.platform.camelk.sources.builders.xml.beans.ElementBeansBuilder;
import org.qubership.integration.platform.chain.model.Chain;
import org.qubership.integration.platform.chain.model.Element;
import org.qubership.integration.platform.chain.model.Snapshot;
import org.qubership.integration.platform.io.writers.camel.xml.BuilderConstants;
import org.qubership.integration.platform.library.components.LibraryElementsService;
import org.qubership.integration.platform.library.model.ElementDescriptor;
import org.qubership.integration.platform.library.model.ElementType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static org.qubership.integration.platform.library.constants.CamelNames.CONTAINER;
import static org.qubership.integration.platform.library.constants.ConfigurationPropertiesConstants.ELEMENTS_WITH_INTERMEDIATE_CHILDREN;

@Component
public class CommonBeansBuilder implements ElementBeansBuilder {
    private final LibraryElementsService libraryService;

    @Autowired
    public CommonBeansBuilder(LibraryElementsService libraryService) {
        this.libraryService = libraryService;
    }

    @Override
    public boolean applicableTo(Element element) {
        return true;
    }

    @Override
    public void build(
            XMLStreamWriter2 streamWriter,
            Element element,
            SourceBuilderContext context
    ) throws Exception {
        streamWriter.writeStartElement("bean");
        streamWriter.writeAttribute("name", "ElementInfo-" + element.getId());
        streamWriter.writeAttribute("type", "org.qubership.integration.platform.engine.metadata.ElementInfo");

        streamWriter.writeStartElement("properties");

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "id");
        streamWriter.writeAttribute("value", element.getOriginalId().orElse(element.getId()));

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "snapshotElementId");
        streamWriter.writeAttribute("value", element.getId());

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "name");
        streamWriter.writeAttribute("value", element.getName());

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "type");
        streamWriter.writeAttribute("value", element.getType());

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "chainId");
        streamWriter.writeAttribute("value", element.getSnapshot()
            .map(Snapshot::getChain)
            .map(Chain::getId)
            .orElse(""));

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "snapshotId");
        streamWriter.writeAttribute("value", element.getSnapshot()
            .map(Snapshot::getId)
            .orElse(""));

        if (element.getParent().isPresent()) {
            Element parent = element.getParent().get();
            if (CONTAINER.equals(parent.getType())
                || libraryService.lookupElementDescriptor(parent.getType())
                .map(ElementDescriptor::getType)
                .map(ElementType.REUSE::equals)
                .orElse(false)) {
                streamWriter.writeEmptyElement("property");
                streamWriter.writeAttribute("key", "parentId");
                streamWriter.writeAttribute("value", parent.getOriginalId().orElse(parent.getId()));

                streamWriter.writeEmptyElement("property");
                streamWriter.writeAttribute("key", "hasIntermediateParents");
                streamWriter.writeAttribute("value",
                    Boolean.toString(ELEMENTS_WITH_INTERMEDIATE_CHILDREN
                        .contains(parent.getType())));

            }

            if (BuilderConstants.REUSE_ELEMENT_TYPE.equals(parent.getType())) {
                streamWriter.writeEmptyElement("property");
                streamWriter.writeAttribute("key", "reuseId");
                streamWriter.writeAttribute("value", parent.getOriginalId().orElse(parent.getId()));
            }
        }

        streamWriter.writeEndElement();
        streamWriter.writeEndElement();
    }
}

package org.qubership.integration.platform.camelk.sources.builders.xml.beans.builders.element;

import org.codehaus.stax2.XMLStreamWriter2;
import org.qubership.integration.platform.camelk.sources.SourceBuilderContext;
import org.qubership.integration.platform.camelk.sources.builders.xml.beans.ElementBeansBuilder;
import org.qubership.integration.platform.chain.impl.ElementBuilder;
import org.qubership.integration.platform.chain.model.Element;
import org.qubership.integration.platform.library.components.LibraryElementsService;
import org.qubership.integration.platform.library.model.ElementDescriptor;
import org.qubership.integration.platform.library.model.ElementType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class CompositeTriggerBeansBuilder implements ElementBeansBuilder {
    private final LibraryElementsService libraryService;
    private final CommonBeansBuilder commonBeansBuilder;

    @Autowired
    public CompositeTriggerBeansBuilder(
        LibraryElementsService libraryService,
        CommonBeansBuilder commonBeansBuilder
    ) {
        this.libraryService = libraryService;
        this.commonBeansBuilder = commonBeansBuilder;
    }

    @Override
    public boolean applicableTo(Element element) {
        ElementDescriptor descriptor = libraryService.getElementDescriptor(element.getType());
        return ElementType.COMPOSITE_TRIGGER.equals(descriptor.getType());
    }

    @Override
    public void build(
        XMLStreamWriter2 streamWriter,
        Element element,
        SourceBuilderContext context
    ) throws Exception {
        String id = UUID.nameUUIDFromBytes(element.getId().getBytes()).toString();
        Element trigger = ElementBuilder.createNew()
            .from(element)
            .id(id)
            .build();
        commonBeansBuilder.build(streamWriter, trigger, context);
    }
}

package org.qubership.integration.platform.runtime.catalog.cr.sources.builders.xml.beans.builders.element;

import org.codehaus.stax2.XMLStreamWriter2;
import org.qubership.integration.platform.runtime.catalog.cr.sources.SourceBuilderContext;
import org.qubership.integration.platform.runtime.catalog.cr.sources.builders.xml.beans.ElementBeansBuilder;
import org.qubership.integration.platform.runtime.catalog.model.library.ElementDescriptor;
import org.qubership.integration.platform.runtime.catalog.model.library.ElementType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.service.library.LibraryElementsService;
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
    public boolean applicableTo(ChainElement element) {
        ElementDescriptor descriptor = libraryService.getElementDescriptor(element);
        return ElementType.COMPOSITE_TRIGGER.equals(descriptor.getType());
    }

    @Override
    public void build(
        XMLStreamWriter2 streamWriter,
        ChainElement element,
        SourceBuilderContext context
    ) throws Exception {
        ChainElement trigger = element.copyWithOriginalId();
        String id = UUID.nameUUIDFromBytes(element.getId().getBytes()).toString();
        trigger.setId(id);
        trigger.getOutputDependencies().addAll(element.getOutputDependencies());
        commonBeansBuilder.build(streamWriter, trigger, context);
    }
}

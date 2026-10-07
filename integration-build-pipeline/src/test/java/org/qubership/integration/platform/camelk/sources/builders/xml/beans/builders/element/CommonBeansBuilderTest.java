package org.qubership.integration.platform.camelk.sources.builders.xml.beans.builders.element;

import com.ctc.wstx.stax.WstxOutputFactory;
import org.codehaus.stax2.XMLStreamWriter2;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.camelk.sources.SourceBuilderContext;
import org.qubership.integration.platform.chain.impl.ElementBuilder;
import org.qubership.integration.platform.chain.model.Element;
import org.qubership.integration.platform.library.components.LibraryElementsService;
import org.qubership.integration.platform.library.model.ElementDescriptor;
import org.qubership.integration.platform.library.model.ElementType;

import java.io.StringWriter;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommonBeansBuilderTest {

    @Mock
    private LibraryElementsService libraryService;

    @InjectMocks
    private CommonBeansBuilder builder;

    @Test
    void elementInsideAsyncBranchGetsBranchElementIdAsParent() throws Exception {
        String xml = build(childOf("async-split-element-2"));

        assertTrue(xml.contains("key=\"parentId\" value=\"parent-id\""));
    }

    @Test
    void elementInsideGroupContainerHasNoParent() throws Exception {
        String xml = build(childOf("container"));

        assertFalse(xml.contains("key=\"parentId\""));
    }

    @Test
    void elementInsideReuseHasReuseIdAndNoParent() throws Exception {
        ElementDescriptor reuse = new ElementDescriptor();
        reuse.setType(ElementType.REUSE);
        when(libraryService.lookupElementDescriptor("reuse")).thenReturn(Optional.of(reuse));

        String xml = build(childOf("reuse"));

        assertFalse(xml.contains("key=\"parentId\""));
        assertTrue(xml.contains("key=\"reuseId\" value=\"parent-original-id\""));
    }

    private static Element childOf(String parentType) {
        Element parent = ElementBuilder.createNew()
                .id("parent-id")
                .originalId("parent-original-id")
                .type(parentType)
                .build();
        return ElementBuilder.createNew()
                .id("child-id")
                .name("Child")
                .type("script")
                .parent(parent)
                .build();
    }

    private String build(Element element) throws Exception {
        StringWriter result = new StringWriter();
        XMLStreamWriter2 writer = (XMLStreamWriter2) new WstxOutputFactory().createXMLStreamWriter(result);
        builder.build(writer, element, SourceBuilderContext.builder().build());
        writer.flush();
        return result.toString();
    }
}

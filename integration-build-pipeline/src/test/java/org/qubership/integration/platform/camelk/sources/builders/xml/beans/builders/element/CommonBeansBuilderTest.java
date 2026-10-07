package org.qubership.integration.platform.camelk.sources.builders.xml.beans.builders.element;

import com.ctc.wstx.stax.WstxOutputFactory;
import org.codehaus.stax2.XMLStreamWriter2;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class CommonBeansBuilderTest {

    private static final String ELEMENT_ID = "11111111-1111-1111-1111-111111111111";
    private static final String PARENT_ID = "22222222-2222-2222-2222-222222222222";
    private static final String PARENT_ORIGINAL_ID = "33333333-3333-3333-3333-333333333333";

    @Mock
    private LibraryElementsService libraryService;

    private CommonBeansBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new CommonBeansBuilder(libraryService);
        ElementDescriptor reuse = new ElementDescriptor();
        reuse.setType(ElementType.REUSE);
        lenient().when(libraryService.lookupElementDescriptor("reuse")).thenReturn(Optional.of(reuse));
    }

    @Test
    void shouldWriteSnapshotIdOfRuntimeParent() throws Exception {
        String xml = build(element(parent("try-catch-finally-2")));

        assertTrue(xml.contains("<property key=\"parentId\" value=\"" + PARENT_ID + "\"/>"), xml);
        assertTrue(xml.contains("<property key=\"hasIntermediateParents\" value=\"false\"/>"), xml);
        assertFalse(xml.contains("reuseId"), xml);
    }

    @Test
    void shouldMarkParentWithIntermediateChildren() throws Exception {
        String xml = build(element(parent("loop-2")));

        assertTrue(xml.contains("<property key=\"parentId\" value=\"" + PARENT_ID + "\"/>"), xml);
        assertTrue(xml.contains("<property key=\"hasIntermediateParents\" value=\"true\"/>"), xml);
    }

    @Test
    void shouldWriteNoParentIdForGroupContainer() throws Exception {
        String xml = build(element(parent("container")));

        assertFalse(xml.contains("parentId"), xml);
        assertFalse(xml.contains("hasIntermediateParents"), xml);
    }

    @Test
    void shouldWriteOnlyReuseIdForReuseParent() throws Exception {
        String xml = build(element(parent("reuse")));

        assertFalse(xml.contains("parentId"), xml);
        assertTrue(xml.contains("<property key=\"reuseId\" value=\"" + PARENT_ORIGINAL_ID + "\"/>"), xml);
    }

    private Element parent(String type) {
        return ElementBuilder.createNew().id(PARENT_ID).originalId(PARENT_ORIGINAL_ID).type(type).build();
    }

    private Element element(Element parent) {
        return ElementBuilder.createNew().id(ELEMENT_ID).name("Step").type("script").parent(parent).build();
    }

    private String build(Element element) throws Exception {
        StringWriter result = new StringWriter();
        XMLStreamWriter2 writer = (XMLStreamWriter2) new WstxOutputFactory().createXMLStreamWriter(result);
        writer.writeStartElement("beans");
        builder.build(writer, element, SourceBuilderContext.builder().build());
        writer.writeEndElement();
        writer.flush();
        return result.toString();
    }
}

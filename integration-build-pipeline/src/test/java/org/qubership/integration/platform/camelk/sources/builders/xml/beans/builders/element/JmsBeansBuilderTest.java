package org.qubership.integration.platform.camelk.sources.builders.xml.beans.builders.element;

import com.ctc.wstx.stax.WstxOutputFactory;
import org.codehaus.stax2.XMLStreamWriter2;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.qubership.integration.platform.camelk.sources.SourceBuilderContext;
import org.qubership.integration.platform.chain.impl.ElementBuilder;
import org.qubership.integration.platform.chain.model.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.qubership.integration.platform.library.constants.CamelNames.JMS_SENDER_COMPONENT;
import static org.qubership.integration.platform.library.constants.CamelNames.JMS_TRIGGER_COMPONENT;

class JmsBeansBuilderTest {
    private final JmsBeansBuilder builder = new JmsBeansBuilder();

    @ParameterizedTest
    @MethodSource("jmsCredentials")
    void shouldPreserveProvidedPropertiesWhenCredentialsAreOptional(String type, Map<String, String> credentials) throws Exception {
        Map<String, Object> properties = new HashMap<>(Map.of(
                "initialContextFactory", "example.InitialContextFactory",
                "providerUrl", "amqp://localhost:5672",
                "connectionFactoryName", "ConnectionFactory"));
        properties.putAll(credentials);
        Element element = ElementBuilder.createNew()
                .id("11111111-1111-1111-1111-111111111111")
                .originalId("22222222-2222-2222-2222-222222222222")
                .type(type)
                .properties(properties)
                .build();

        Map<String, String> actual = buildProperties(element);

        Map<String, Object> expected = new HashMap<>(properties);
        expected.put("elementId", "22222222-2222-2222-2222-222222222222");
        assertEquals(expected, actual);
    }

    private static Stream<Arguments> jmsCredentials() {
        return Stream.<Map<String, String>>of(
                Map.of(),
                Map.of("username", "test-user"),
                Map.of("password", "test-pass"),
                Map.of("username", "test-user", "password", "test-pass"),
                Map.of("username", "", "password", ""))
                .flatMap(credentials -> Stream.of(JMS_TRIGGER_COMPONENT, JMS_SENDER_COMPONENT)
                        .map(type -> arguments(type, credentials)));
    }

    private Map<String, String> buildProperties(Element element) throws Exception {
        StringWriter output = new StringWriter();
        XMLStreamWriter2 writer = (XMLStreamWriter2) new WstxOutputFactory().createXMLStreamWriter(output);
        try {
            builder.build(writer, element, SourceBuilderContext.builder().build());
            writer.flush();
        } finally {
            writer.close();
        }
        NodeList nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new InputSource(new StringReader(output.toString())))
                .getElementsByTagName("property");
        Map<String, String> properties = new HashMap<>();
        for (int index = 0; index < nodes.getLength(); index++) {
            org.w3c.dom.Element property = (org.w3c.dom.Element) nodes.item(index);
            properties.put(property.getAttribute("key"), property.getAttribute("value"));
        }
        return properties;
    }
}

package org.qubership.integration.platform.camelk.sources.builders.xml.beans;

import org.codehaus.stax2.XMLStreamWriter2;

import javax.xml.stream.XMLStreamException;

import static org.qubership.integration.platform.camelk.sources.builders.xml.beans.XmlBeanConstants.*;

public final class BeanPropertyHelper {
    private BeanPropertyHelper() {}

    public static void writePropertyElement(
        XMLStreamWriter2 streamWriter,
        String key,
        String value
    ) throws XMLStreamException {
        streamWriter.writeEmptyElement(XML_PROPERTY);
        streamWriter.writeAttribute(ATTR_KEY, key);
        streamWriter.writeAttribute(ATTR_VALUE, value);
    }
}

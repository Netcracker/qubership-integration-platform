package org.qubership.integration.platform.runtime.catalog.cr.sources.builders.xml.beans.builders.element;

import com.ctc.wstx.stax.WstxOutputFactory;
import org.codehaus.stax2.XMLStreamWriter2;
import org.qubership.integration.platform.runtime.catalog.cr.sources.SourceBuilderContext;
import org.qubership.integration.platform.runtime.catalog.cr.sources.builders.xml.beans.ElementBeansBuilder;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;

import java.io.StringWriter;

/**
 * Runs an {@link ElementBeansBuilder} against a real Woodstox writer and returns the emitted XML.
 *
 * <p>The writer must be Woodstox, not a mock: several of these tests exist because Woodstox rejects
 * calls that a lenient writer accepts (an unbound namespace URI in {@code writeEmptyElement}, a null
 * {@code writeAttribute} value). Substituting the writer would silently retire the regression.
 */
final class BeanXmlTestSupport {

    private BeanXmlTestSupport() {
    }

    static String buildXml(ElementBeansBuilder builder, ChainElement element) throws Exception {
        StringWriter result = new StringWriter();
        XMLStreamWriter2 streamWriter = (XMLStreamWriter2) new WstxOutputFactory().createXMLStreamWriter(result);
        streamWriter.writeStartDocument();
        streamWriter.writeStartElement("test-root");
        builder.build(streamWriter, element, SourceBuilderContext.builder().build());
        streamWriter.writeEndElement();
        streamWriter.writeEndDocument();
        streamWriter.flush();
        streamWriter.close();
        return result.toString();
    }
}

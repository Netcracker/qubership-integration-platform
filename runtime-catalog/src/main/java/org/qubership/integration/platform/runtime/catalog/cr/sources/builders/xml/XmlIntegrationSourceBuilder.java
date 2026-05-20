package org.qubership.integration.platform.runtime.catalog.cr.sources.builders.xml;

import com.ctc.wstx.stax.WstxOutputFactory;
import org.codehaus.stax2.XMLStreamWriter2;
import org.qubership.integration.platform.runtime.catalog.builder.ChainRouteBuilder;
import org.qubership.integration.platform.runtime.catalog.builder.XmlBuilder;
import org.qubership.integration.platform.runtime.catalog.cr.sources.IntegrationSourceBuilder;
import org.qubership.integration.platform.runtime.catalog.cr.sources.SourceBuilderContext;
import org.qubership.integration.platform.runtime.catalog.cr.sources.builders.xml.beans.ElementBeansBuilder;
import org.qubership.integration.platform.runtime.catalog.cr.sources.builders.xml.beans.ElementBeansBuilderFactory;
import org.qubership.integration.platform.runtime.catalog.cr.sources.builders.xml.beans.SnapshotBeanBuilder;
import org.qubership.integration.platform.runtime.catalog.model.ChainRoute;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.StringWriter;
import java.util.Collection;
import java.util.List;

@Component
public class XmlIntegrationSourceBuilder implements IntegrationSourceBuilder {
    private static final String SCHEMA = "https://camel.apache.org/schema/xml-io";
    private static final String CAMEL = "camel";

    private final ChainRouteBuilder chainRouteBuilder;
    private final XmlBuilder xmlBuilder;
    private final ElementBeansBuilderFactory elementBeansBuilderFactory;
    private final Collection<SnapshotBeanBuilder> snapshotBeanBuilders;

    @Autowired
    public XmlIntegrationSourceBuilder(
            ChainRouteBuilder chainRouteBuilder,
            XmlBuilder xmlBuilder,
            Collection<SnapshotBeanBuilder> snapshotBeanBuilders,
            ElementBeansBuilderFactory elementBeansBuilderFactory
    ) {
        this.chainRouteBuilder = chainRouteBuilder;
        this.xmlBuilder = xmlBuilder;
        this.snapshotBeanBuilders = snapshotBeanBuilders;
        this.elementBeansBuilderFactory = elementBeansBuilderFactory;
    }

    @Override
    public String getLanguageName() {
        return "xml";
    }

    @Override
    public String build(Snapshot snapshot, SourceBuilderContext context) throws Exception {
        return buildContent(snapshot, context);
    }

    private String buildContent(Snapshot snapshot, SourceBuilderContext context) throws Exception {
        StringWriter result = new StringWriter();
        XMLStreamWriter2 streamWriter = (XMLStreamWriter2) new WstxOutputFactory().createXMLStreamWriter(result);
        streamWriter.writeStartDocument();
        streamWriter.writeStartElement(CAMEL);
        streamWriter.writeDefaultNamespace(SCHEMA);

        List<ChainRoute> routes = chainRouteBuilder.build(snapshot.getElements());
        routes.forEach(route -> route.setGroup(snapshot.getId())); // TODO check that it doesn't break anything on engine side

        writeBeans(streamWriter, snapshot, context);
        xmlBuilder.buildRoutesContent(streamWriter, routes);

        streamWriter.writeEndElement();
        streamWriter.writeEndDocument();
        streamWriter.flush();
        streamWriter.close();

        return result.toString();
    }

    private void writeBeans(
            XMLStreamWriter2 streamWriter,
            Snapshot snapshot,
            SourceBuilderContext context
    ) throws Exception {
        writeChainBeans(streamWriter, snapshot, context);
        for (ChainElement element : snapshot.getElements()) {
            writeChainElementBeans(streamWriter, element, context);
        }
    }

    private void writeChainBeans(
            XMLStreamWriter2 streamWriter,
            Snapshot snapshot,
            SourceBuilderContext context
    ) throws Exception {
        for (SnapshotBeanBuilder builder : snapshotBeanBuilders) {
            builder.build(streamWriter, snapshot, context);
        }
    }

    private void writeChainElementBeans(
            XMLStreamWriter2 streamWriter,
            ChainElement element,
            SourceBuilderContext context
    ) throws Exception {
        ElementBeansBuilder builder = elementBeansBuilderFactory.getElementBeansBuilder(element);
        builder.build(streamWriter, element, context);
    }
}

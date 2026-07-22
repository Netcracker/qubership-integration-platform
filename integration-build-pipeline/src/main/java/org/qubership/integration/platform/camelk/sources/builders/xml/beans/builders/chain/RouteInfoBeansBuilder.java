package org.qubership.integration.platform.camelk.sources.builders.xml.beans.builders.chain;

import org.codehaus.stax2.XMLStreamWriter2;
import org.qubership.integration.platform.camelk.model.routes.Route;
import org.qubership.integration.platform.camelk.services.RoutesGetterService;
import org.qubership.integration.platform.camelk.sources.SourceBuilderContext;
import org.qubership.integration.platform.camelk.sources.builders.xml.beans.SnapshotBeanBuilder;
import org.qubership.integration.platform.chain.model.Snapshot;
import org.springframework.stereotype.Component;

import java.util.Collection;
import javax.xml.stream.XMLStreamException;

@Component
public class RouteInfoBeansBuilder implements SnapshotBeanBuilder {
    private final RoutesGetterService routesGetterService;

    public RouteInfoBeansBuilder(RoutesGetterService routesGetterService) {
        this.routesGetterService = routesGetterService;
    }

    @Override
    public void build(XMLStreamWriter2 streamWriter, Snapshot snapshot, SourceBuilderContext context) throws Exception {
        Collection<Route> routes = routesGetterService.getRoutes(snapshot, context.getIntegrationServiceCatalog());
        for (Route route : routes) {
            addRouteRegistrationInfoBean(streamWriter, snapshot, route);
        }
    }

    private void addRouteRegistrationInfoBean(
            XMLStreamWriter2 streamWriter,
            Snapshot snapshot,
            Route route
    ) throws XMLStreamException {
        streamWriter.writeStartElement("bean");
        streamWriter.writeAttribute("name", "RouteRegistrationInfo-" + route.getId());
        streamWriter.writeAttribute("type", "org.qubership.integration.platform.engine.metadata.RouteRegistrationInfo");

        streamWriter.writeStartElement("properties");

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "snapshotId");
        streamWriter.writeAttribute("value", snapshot.getId());

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "path");
        streamWriter.writeAttribute("value", route.getPath());

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "gatewayPrefix");
        streamWriter.writeAttribute("value", String.valueOf(route.getGatewayPrefix()));

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "variableName");
        streamWriter.writeAttribute("value", String.valueOf(route.getVariableName()));

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "type");
        streamWriter.writeAttribute("value", route.getType().name());

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "connectTimeout");
        streamWriter.writeAttribute("value", Long.toString(route.getConnectTimeout()));

        streamWriter.writeEndElement();
        streamWriter.writeEndElement();
    }
}

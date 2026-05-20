/*
 * Copyright 2024-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.qubership.integration.platform.runtime.catalog.builder;

import com.ctc.wstx.stax.WstxOutputFactory;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.codehaus.stax2.XMLStreamWriter2;
import org.qubership.integration.platform.runtime.catalog.builder.templates.TemplateService;
import org.qubership.integration.platform.runtime.catalog.consul.ConfigurationPropertiesConstants;
import org.qubership.integration.platform.runtime.catalog.model.ChainRoute;
import org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames;
import org.qubership.integration.platform.runtime.catalog.model.library.ElementDescriptor;
import org.qubership.integration.platform.runtime.catalog.model.library.ElementType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ContainerChainElement;
import org.qubership.integration.platform.runtime.catalog.service.library.LibraryElementsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringWriter;
import java.util.*;
import javax.xml.stream.XMLStreamException;

import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames.CONTAINER;

@Slf4j
@Component
public class XmlBuilder {
    private final ChainRouteBuilder chainRouteBuilder;
    private final TemplateService templateService;
    private final LibraryElementsService libraryService;

    @Autowired
    public XmlBuilder(
            ChainRouteBuilder chainRouteBuilder,
            TemplateService templateService,
            LibraryElementsService libraryService
    ) {
        this.chainRouteBuilder = chainRouteBuilder;
        this.templateService = templateService;
        this.libraryService = libraryService;
    }

    public String build(List<ChainElement> elements) throws XMLStreamException, IOException {
        StringWriter result = new StringWriter();
        XMLStreamWriter2 streamWriter = (XMLStreamWriter2) new WstxOutputFactory().createXMLStreamWriter(result);
        streamWriter.writeStartDocument();
        streamWriter.writeStartElement(BuilderConstants.ROUTES);
        streamWriter.writeDefaultNamespace(BuilderConstants.SCHEMA);

        List<ChainRoute> routes = chainRouteBuilder.build(elements);
        buildRoutesContent(streamWriter, routes);

        streamWriter.writeEndElement();
        streamWriter.writeEndDocument();
        streamWriter.flush();
        streamWriter.close();

        return result.toString();
    }

    public void buildRoutesContent(XMLStreamWriter2 streamWriter, List<ChainRoute> routes) throws XMLStreamException {
        for (ChainRoute chainRoute : routes) {
            streamWriter.writeStartElement(BuilderConstants.ROUTE);
            if (StringUtils.isNotBlank(chainRoute.getCustomIdPlaceholder())) {
                streamWriter.writeAttribute(
                        BuilderConstants.ID,
                        chainRoute.getCustomIdPlaceholder());
            }
            if (StringUtils.isNotBlank(chainRoute.getGroup())) {
                streamWriter.writeAttribute(
                        BuilderConstants.GROUP,
                        chainRoute.getGroup());
            }
            if (isRouteReferencedFromAnother(chainRoute)) {
                streamWriter.writeEmptyElement(BuilderConstants.FROM);
                streamWriter.writeAttribute(BuilderConstants.URI, BuilderConstants.DIRECT + chainRoute.getId());
            }
            for (ChainElement chainElement : chainRoute.getElements()) {
                ElementDescriptor elementDescriptor = libraryService.getElementDescriptor(chainElement);
                ElementType type = elementDescriptor.getType();
                if (type == ElementType.TRIGGER && !BuilderConstants.ON_COMPLETION_EXCLUDE_TRIGGERS.contains(elementDescriptor.getName())) {
                    addOnCompletion(streamWriter);
                    addChainStart(streamWriter);
                }
                if (type != ElementType.CONTAINER) {
                    streamWriter.writeRaw(templateService.applyTemplate(chainElement));
                }
            }
            if (chainRoute.getNextRoutes().size() > 1) {
                streamWriter.writeStartElement(BuilderConstants.MULTICAST);
            }
            for (ChainRoute childRoute : chainRoute.getNextRoutes()) {
                streamWriter.writeEmptyElement(BuilderConstants.TO);
                streamWriter.writeAttribute(BuilderConstants.URI, BuilderConstants.DIRECT + childRoute.getId());
            }
            if (chainRoute.getNextRoutes().size() > 1) {
                streamWriter.writeEndElement();
            }
            streamWriter.writeEndElement();

            // add extra route with onCompletion for split async element
            addWiretapBridgeRoute(chainRoute, streamWriter);
        }
    }

    private void addWiretapBridgeRoute(ChainRoute chainRoute, XMLStreamWriter2 streamWriter) throws XMLStreamException {
        for (ChainElement element : chainRoute.getElements()) {
            ElementDescriptor elementDescriptor = libraryService.getElementDescriptor(element);
            String elementName = elementDescriptor.getName();
            if (CamelNames.SPLIT_ASYNC_2_COMPONENT.equals(elementName) || CamelNames.SPLIT_ASYNC_COMPONENT.equals(elementName)) {
                ContainerChainElement splitContainer = (ContainerChainElement) element;
                for (ChainElement splitElement : splitContainer.getElements()) {
                    String splitElementName = libraryService.getElementDescriptor(splitElement).getName();
                    if (ConfigurationPropertiesConstants.ASYNC_SPLIT_ELEMENT.equals(splitElementName)
                            || ConfigurationPropertiesConstants.ASYNC_SPLIT_ELEMENT_2.equals(splitElementName)
                    ) {
                        streamWriter.writeStartElement(BuilderConstants.ROUTE);

                        addOnCompletion(streamWriter);

                        streamWriter.writeEmptyElement(BuilderConstants.FROM);
                        streamWriter.writeAttribute(BuilderConstants.URI,
                                BuilderConstants.DIRECT + splitElement.getId() + BuilderConstants.ON_COMPLETION_ID_POSTFIX);

                        addSplitAsyncStart(streamWriter);

                        streamWriter.writeEmptyElement(BuilderConstants.TO);
                        streamWriter.writeAttribute(BuilderConstants.URI,
                                BuilderConstants.DIRECT + splitElement.getId());

                        streamWriter.writeEndElement();
                    }
                }
            }
        }
    }

    private static void addOnCompletion(XMLStreamWriter2 streamWriter) throws XMLStreamException {
        streamWriter.writeStartElement(BuilderConstants.ON_COMPLETION);
        streamWriter.writeEmptyElement(BuilderConstants.PROCESS);
        streamWriter.writeAttribute(BuilderConstants.REF, BuilderConstants.CHAIN_FINISH_PROCESSOR);
        streamWriter.writeEndElement();
    }

    private static void addChainStart(XMLStreamWriter2 streamWriter) throws XMLStreamException {
        streamWriter.writeEmptyElement(BuilderConstants.PROCESS);
        streamWriter.writeAttribute(BuilderConstants.REF, BuilderConstants.CHAIN_START_PROCESSOR);
    }

    private static void addSplitAsyncStart(XMLStreamWriter2 streamWriter) throws XMLStreamException {
        streamWriter.writeEmptyElement(BuilderConstants.PROCESS);
        streamWriter.writeAttribute(BuilderConstants.REF, BuilderConstants.SPLIT_ASYNC_PROCESSOR);
    }

    private boolean isRouteReferencedFromAnother(ChainRoute route) {
        if (route.getElements().isEmpty()) {
            return true;
        }
        ChainElement routeStart = route.getElements().get(0);
        return !routeStart.getInputDependencies().isEmpty()
                || (routeStart.getParent() != null && !CONTAINER.equals(routeStart.getParent().getType()));
    }
}

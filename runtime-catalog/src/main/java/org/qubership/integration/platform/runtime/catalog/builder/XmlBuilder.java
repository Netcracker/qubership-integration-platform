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
import org.apache.commons.lang3.tuple.Pair;
import org.codehaus.stax2.XMLStreamWriter2;
import org.qubership.integration.platform.runtime.catalog.builder.templates.TemplateService;
import org.qubership.integration.platform.runtime.catalog.consul.ConfigurationPropertiesConstants;
import org.qubership.integration.platform.runtime.catalog.model.ChainRoute;
import org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames;
import org.qubership.integration.platform.runtime.catalog.model.library.ElementDescriptor;
import org.qubership.integration.platform.runtime.catalog.model.library.ElementType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Dependency;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ContainerChainElement;
import org.qubership.integration.platform.runtime.catalog.service.library.LibraryElementsService;
import org.qubership.integration.platform.runtime.catalog.util.ElementUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringWriter;
import java.util.*;
import java.util.stream.Collectors;
import javax.xml.stream.XMLStreamException;

import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames.CONTAINER;

@Slf4j
@Component
public class XmlBuilder {

    private final TemplateService templateService;
    private final LibraryElementsService libraryService;
    private final ElementUtils elementUtils;

    @Autowired
    public XmlBuilder(TemplateService templateService, LibraryElementsService libraryService,
                      ElementUtils elementUtils) {
        this.templateService = templateService;
        this.libraryService = libraryService;
        this.elementUtils = elementUtils;
    }

    public String build(List<ChainElement> elements) throws XMLStreamException, IOException {

        elements = elementUtils.splitCompositeTriggers(elements);
        List<ChainElement> startElements = elements.stream()
                .filter(chainElement -> {
                    ElementDescriptor descriptor = libraryService.getElementDescriptor(chainElement);
                    boolean elementHasNoParent = chainElement.getParent() == null
                            || CONTAINER.equals(chainElement.getParent().getType());
                    return descriptor != null
                            && (descriptor.getType() == ElementType.TRIGGER
                                || descriptor.getType() == ElementType.REUSE
                                || (descriptor.getType() == ElementType.COMPOSITE_TRIGGER
                            && elementHasNoParent
                            && chainElement.getInputDependencies().isEmpty()));
                })
                .collect(Collectors.toList());

        Map<String, String> routesWithCustomIdPlaceholder = new HashMap<>();
        List<ChainRoute> routes = collectRoutes(startElements, routesWithCustomIdPlaceholder);

        StringWriter result = new StringWriter();
        XMLStreamWriter2 streamWriter = (XMLStreamWriter2) new WstxOutputFactory().createXMLStreamWriter(result);
        streamWriter.writeStartDocument();
        streamWriter.writeStartElement(BuilderConstants.ROUTES);
        streamWriter.writeDefaultNamespace(BuilderConstants.SCHEMA);

        for (ChainRoute chainRoute : routes) {
            streamWriter.writeStartElement(BuilderConstants.ROUTE);
            if (routesWithCustomIdPlaceholder.containsKey(chainRoute.getId())) {
                streamWriter.writeAttribute(
                        BuilderConstants.ID,
                        routesWithCustomIdPlaceholder.get(chainRoute.getId()));
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
        streamWriter.writeEndElement();
        streamWriter.writeEndDocument();
        streamWriter.flush();
        streamWriter.close();

        return result.toString();
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

    private List<ChainRoute> collectRoutes(List<ChainElement> startElements, Map<String, String> routesWithCustomId) {
        List<ChainRoute> routes = new LinkedList<>();
        Map<String, ChainRoute> elementToRoute = new HashMap<>(); // map of elements where key is "to" element id and value is its route
        Deque<Pair<ChainElement, ChainRoute>> stack = new LinkedList<>();
        for (ChainElement startElement : startElements) {
            ChainRoute route = !BuilderConstants.REUSE_ELEMENT_TYPE.equals(startElement.getType())
                    ? new ChainRoute()
                    : new ChainRoute(startElement.getOriginalId());
            routes.add(route);
            stack.push(Pair.of(startElement, route));

            if (startElement.getType().startsWith(BuilderConstants.SFTP_TRIGGER_PREFIX)) {
                routesWithCustomId.put(
                        route.getId(),
                        BuilderConstants.DEPLOYMENT_ID_PLACEHOLDER + "-" + startElement.getId());
            }
        }
        while (!stack.isEmpty()) {
            Pair<ChainElement, ChainRoute> currentElement = stack.pop();
            ChainElement current = currentElement.getLeft();
            ChainRoute currentRoute = currentElement.getRight();
            ElementDescriptor elementDescriptor = libraryService.getElementDescriptor(current);
            ElementType elementType = elementDescriptor.getType();

            if (currentRoute.getElements().isEmpty()) {
                elementToRoute.put(current.getId(), currentRoute);
            }
            currentRoute.getElements().add(current);

            //Condition that decide route need to be finished
            boolean completeRoute =
                    elementType == ElementType.TRIGGER
                            || (elementType == ElementType.COMPOSITE_TRIGGER)
                            || current.getOutputDependencies().size() != 1;

            for (Dependency dependency : current.getOutputDependencies()) {
                ChainElement nextElement = dependency.getElementTo();
                if (elementToRoute.containsKey(nextElement.getId())) { // if a route with nextElement already exists
                    ChainRoute nextRoute = elementToRoute.get(nextElement.getId());
                    currentRoute.getNextRoutes().add(nextRoute);
                } else {
                    ChainRoute route = currentRoute;
                    if (completeRoute || nextElement.getInputDependencies().size() > 1) {
                        route = new ChainRoute(); // start new route
                        routes.add(route);
                        currentRoute.getNextRoutes().add(route);
                    }
                    stack.push(Pair.of(dependency.getElementTo(), route));
                }
            }

            if (current instanceof ContainerChainElement && elementType != ElementType.CONTAINER) {
                if (!elementDescriptor.isOldStyleContainer()) {
                    List<ChainRoute> containerRoutes = collectContainerSubRoutes(
                            (ContainerChainElement) current,
                            elementToRoute,
                            stack
                    );
                    routes.addAll(containerRoutes);
                    continue;
                }

                // this block is used for deprecated containers that cannot contain logically nested
                // dependent elements within themselves. It can be removed when such containers are
                // completely removed from the project
                for (ChainElement element : ((ContainerChainElement) current).getElements()) {
                    ChainRoute branchRoute = new ChainRoute(element.getId());
                    routes.add(branchRoute);
                    for (Dependency outputDependency : element.getOutputDependencies()) {
                        ChainElement nextElement = outputDependency.getElementTo();
                        branchRoute.getNextRoutes().add(extractNextRoute(nextElement, routes, elementToRoute, stack));
                    }
                }
            }
        }
        return routes;
    }

    private List<ChainRoute> collectContainerSubRoutes(
            ContainerChainElement containerElement,
            Map<String, ChainRoute> elementToRoute,
            Deque<Pair<ChainElement, ChainRoute>> elementRouteStack
    ) {
        List<ChainRoute> routes = new LinkedList<>();
        ElementDescriptor elementDescriptor = libraryService.getElementDescriptor(containerElement);
        if (!elementDescriptor.getAllowedChildren().isEmpty()) {
            for (ChainElement child : containerElement.getElements()) {
                if (!(child instanceof ContainerChainElement childContainer)) {
                    ChainRoute branchRoute = new ChainRoute(child.getId());
                    routes.add(branchRoute);
                    branchRoute.getNextRoutes().add(extractNextRoute(child, routes, elementToRoute, elementRouteStack));
                    continue;
                }

                addContainerRoutes(routes, childContainer, elementToRoute, elementRouteStack);
            }
            return routes;
        }

        addContainerRoutes(routes, containerElement, elementToRoute, elementRouteStack);
        return routes;
    }

    private void addContainerRoutes(
            List<ChainRoute> routes,
            ContainerChainElement containerElement,
            Map<String, ChainRoute> elementToRoute,
            Deque<Pair<ChainElement, ChainRoute>> elementRouteStack
    ) {
        ChainRoute containerRoute = new ChainRoute(containerElement.getId());
        routes.add(containerRoute);

        List<ChainElement> startElements = containerElement.getElements().stream()
                .filter(element -> element.getInputDependencies().isEmpty())
                .toList();
        if (startElements.size() == 1) {
            elementRouteStack.push(Pair.of(startElements.get(0), containerRoute));
            return;
        }

        for (ChainElement startElement : startElements) {
            ChainRoute nextRoute = extractNextRoute(startElement, routes, elementToRoute, elementRouteStack);
            containerRoute.getNextRoutes().add(nextRoute);
        }
    }

    private ChainRoute extractNextRoute(
            ChainElement element,
            List<ChainRoute> routes,
            Map<String, ChainRoute> elementToRoute,
            Deque<Pair<ChainElement, ChainRoute>> elementRouteStack
    ) {
        if (elementToRoute.containsKey(element.getId())) {
            return elementToRoute.get(element.getId());
        }

        ChainRoute newRoute = new ChainRoute();
        routes.add(newRoute);
        elementRouteStack.push(Pair.of(element, newRoute));
        /*  the nextElement can be 'nextElement' of
          another element in case of merging branches into one element,
          and we need to find existing route in elementToRoute map */
        elementToRoute.put(element.getId(), newRoute);
        return newRoute;
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

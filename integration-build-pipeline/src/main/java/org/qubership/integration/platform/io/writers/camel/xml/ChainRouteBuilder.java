package org.qubership.integration.platform.io.writers.camel.xml;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.qubership.integration.platform.chain.model.Connection;
import org.qubership.integration.platform.chain.model.Element;
import org.qubership.integration.platform.io.writers.camel.xml.model.ChainRoute;
import org.qubership.integration.platform.library.components.LibraryElementsService;
import org.qubership.integration.platform.library.model.ElementDescriptor;
import org.qubership.integration.platform.library.model.ElementType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

import static org.qubership.integration.platform.library.constants.CamelNames.CONTAINER;

@Slf4j
@Component
public class ChainRouteBuilder {
    private final LibraryElementsService libraryService;
    private final CompositeTriggerHelper compositeTriggerHelper;

    @Autowired
    public ChainRouteBuilder(
            LibraryElementsService libraryService,
            CompositeTriggerHelper compositeTriggerHelper
    ) {
        this.libraryService = libraryService;
        this.compositeTriggerHelper = compositeTriggerHelper;
    }

    public List<ChainRoute> build(Collection<? extends Element> elements) {
        List<Element> startElements = compositeTriggerHelper.splitCompositeTriggers(elements)
                .stream()
                .filter(element -> {
                    boolean elementHasNoParent = element.getParent()
                        .map(Element::getType)
                        .map(CONTAINER::equals)
                        .orElse(true);
                    return libraryService.lookupElementDescriptor(element.getType()).map(descriptor ->
                            descriptor.getType() == ElementType.TRIGGER
                            || descriptor.getType() == ElementType.REUSE
                            || (descriptor.getType() == ElementType.COMPOSITE_TRIGGER
                            && elementHasNoParent
                            && element.getInputConnections().isEmpty()))
                        .orElse(false);
                })
                .collect(Collectors.toList());

        return collectRoutes(startElements);
    }

    private List<ChainRoute> collectRoutes(List<Element> startElements) {
        List<ChainRoute> routes = new LinkedList<>();
        Map<String, ChainRoute> elementToRoute = new HashMap<>(); // map of elements where key is "to" element id and value is its route
        Deque<Pair<Element, ChainRoute>> stack = new LinkedList<>();
        for (Element startElement : startElements) {
            ChainRoute route = !BuilderConstants.REUSE_ELEMENT_TYPE.equals(startElement.getType())
                    ? new ChainRoute()
                    : new ChainRoute(startElement.getOriginalId().orElse(startElement.getId()));
            routes.add(route);
            stack.push(Pair.of(startElement, route));

            if (startElement.getType().startsWith(BuilderConstants.SFTP_TRIGGER_PREFIX)) {
                route.setCustomIdPlaceholder(BuilderConstants.DEPLOYMENT_ID_PLACEHOLDER + "-" + startElement.getId());
            }
        }
        while (!stack.isEmpty()) {
            Pair<Element, ChainRoute> currentElement = stack.pop();
            Element current = currentElement.getLeft();
            ChainRoute currentRoute = currentElement.getRight();
            ElementDescriptor elementDescriptor = libraryService.getElementDescriptorOrDefault(current.getType());
            ElementType elementType = elementDescriptor.getType();

            if (currentRoute.getElements().isEmpty()) {
                elementToRoute.put(current.getId(), currentRoute);
            }
            currentRoute.getElements().add(current);

            //Condition that decide route need to be finished
            boolean completeRoute =
                    elementType == ElementType.TRIGGER
                            || (elementType == ElementType.COMPOSITE_TRIGGER)
                            || current.getOutputConnections().size() != 1;

            for (Connection connection : current.getOutputConnections()) {
                Element nextElement = connection.getTo();
                if (elementToRoute.containsKey(nextElement.getId())) { // if a route with nextElement already exists
                    ChainRoute nextRoute = elementToRoute.get(nextElement.getId());
                    currentRoute.getNextRoutes().add(nextRoute);
                } else {
                    ChainRoute route = currentRoute;
                    if (completeRoute || nextElement.getInputConnections().size() > 1) {
                        route = new ChainRoute(); // start new route
                        routes.add(route);
                        currentRoute.getNextRoutes().add(route);
                    }
                    stack.push(Pair.of(connection.getTo(), route));
                }
            }

            if (current.isContainer() && elementType != ElementType.CONTAINER) {
                if (!elementDescriptor.isOldStyleContainer()) {
                    List<ChainRoute> containerRoutes = collectContainerSubRoutes(
                            current,
                            elementToRoute,
                            stack
                    );
                    routes.addAll(containerRoutes);
                    continue;
                }

                // this block is used for deprecated containers that cannot contain logically nested
                // dependent elements within themselves. It can be removed when such containers are
                // completely removed from the project
                for (Element element : current.getChildren()) {
                    ChainRoute branchRoute = new ChainRoute(element.getId());
                    routes.add(branchRoute);
                    for (Connection outputConnection : element.getOutputConnections()) {
                        Element nextElement = outputConnection.getTo();
                        branchRoute.getNextRoutes().add(extractNextRoute(nextElement, routes, elementToRoute, stack));
                    }
                }
            }
        }
        return routes;
    }

    private List<ChainRoute> collectContainerSubRoutes(
            Element containerElement,
            Map<String, ChainRoute> elementToRoute,
            Deque<Pair<Element, ChainRoute>> elementRouteStack
    ) {
        List<ChainRoute> routes = new LinkedList<>();
        ElementDescriptor elementDescriptor = libraryService.getElementDescriptorOrDefault(containerElement.getType());
        if (!elementDescriptor.getAllowedChildren().isEmpty()) {
            for (Element child : containerElement.getChildren()) {
                if (!child.isContainer()) {
                    ChainRoute branchRoute = new ChainRoute(child.getId());
                    routes.add(branchRoute);
                    branchRoute.getNextRoutes().add(extractNextRoute(child, routes, elementToRoute, elementRouteStack));
                    continue;
                }

                addContainerRoutes(routes, child, elementToRoute, elementRouteStack);
            }
            return routes;
        }

        addContainerRoutes(routes, containerElement, elementToRoute, elementRouteStack);
        return routes;
    }

    private void addContainerRoutes(
            List<ChainRoute> routes,
            Element containerElement,
            Map<String, ChainRoute> elementToRoute,
            Deque<Pair<Element, ChainRoute>> elementRouteStack
    ) {
        ChainRoute containerRoute = new ChainRoute(containerElement.getId());
        routes.add(containerRoute);

        List<Element> startElements = containerElement.getChildren().stream()
                .filter(element -> element.getInputConnections().isEmpty())
                .toList();
        if (startElements.size() == 1) {
            elementRouteStack.push(Pair.of(startElements.getFirst(), containerRoute));
            return;
        }

        for (Element startElement : startElements) {
            ChainRoute nextRoute = extractNextRoute(startElement, routes, elementToRoute, elementRouteStack);
            containerRoute.getNextRoutes().add(nextRoute);
        }
    }

    private ChainRoute extractNextRoute(
            Element element,
            List<ChainRoute> routes,
            Map<String, ChainRoute> elementToRoute,
            Deque<Pair<Element, ChainRoute>> elementRouteStack
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
}

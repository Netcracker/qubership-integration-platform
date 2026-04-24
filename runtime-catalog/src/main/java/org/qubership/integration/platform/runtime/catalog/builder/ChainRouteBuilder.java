package org.qubership.integration.platform.runtime.catalog.builder;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.qubership.integration.platform.runtime.catalog.model.ChainRoute;
import org.qubership.integration.platform.runtime.catalog.model.library.ElementDescriptor;
import org.qubership.integration.platform.runtime.catalog.model.library.ElementType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Dependency;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ContainerChainElement;
import org.qubership.integration.platform.runtime.catalog.service.library.LibraryElementsService;
import org.qubership.integration.platform.runtime.catalog.util.ElementUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames.CONTAINER;

@Slf4j
@Component
public class ChainRouteBuilder {
    private final LibraryElementsService libraryService;
    private final ElementUtils elementUtils;

    @Autowired
    public ChainRouteBuilder(
            LibraryElementsService libraryService,
            ElementUtils elementUtils
    ) {
        this.libraryService = libraryService;
        this.elementUtils = elementUtils;
    }

    public List<ChainRoute> build(List<ChainElement> elements) {
        List<ChainElement> startElements = elementUtils.splitCompositeTriggers(elements)
                .stream()
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

        return collectRoutes(startElements);
    }

    private List<ChainRoute> collectRoutes(List<ChainElement> startElements) {
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
                route.setCustomIdPlaceholder(BuilderConstants.DEPLOYMENT_ID_PLACEHOLDER + "-" + startElement.getId());
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
}

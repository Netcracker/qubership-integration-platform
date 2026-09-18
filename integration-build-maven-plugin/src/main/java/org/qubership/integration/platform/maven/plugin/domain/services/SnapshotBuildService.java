package org.qubership.integration.platform.maven.plugin.domain.services;

import org.qubership.integration.platform.camelk.sources.IntegrationServiceCatalog;
import org.qubership.integration.platform.chain.impl.ConnectionImpl;
import org.qubership.integration.platform.chain.impl.ElementImpl;
import org.qubership.integration.platform.chain.impl.ServiceEnvironmentImpl;
import org.qubership.integration.platform.chain.model.*;
import org.qubership.integration.platform.library.components.ElementDescriptorHelper;
import org.qubership.integration.platform.library.model.ElementDescriptor;
import org.qubership.integration.platform.library.model.ElementType;
import org.qubership.integration.platform.maven.plugin.domain.adapters.SnapshotImpl;
import org.qubership.integration.platform.verification.ElementPropertiesVerificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.qubership.integration.platform.library.constants.CamelNames.*;
import static org.qubership.integration.platform.library.constants.CamelOptions.SYSTEM_ID;

@Service
public class SnapshotBuildService {
    private final ElementDescriptorHelper elementDescriptorHelper;
    private final ElementPropertiesVerificationService elementPropertiesVerificationService;
    private final IntegrationServiceCatalog integrationServiceCatalog;

    @Autowired
    public SnapshotBuildService(
        ElementDescriptorHelper elementDescriptorHelper,
        ElementPropertiesVerificationService elementPropertiesVerificationService,
        IntegrationServiceCatalog integrationServiceCatalog
    ) {
        this.elementDescriptorHelper = elementDescriptorHelper;
        this.elementPropertiesVerificationService = elementPropertiesVerificationService;
        this.integrationServiceCatalog = integrationServiceCatalog;
    }

    public Snapshot build(Chain chain) {
        verifyElementProperties(chain);

        SnapshotImpl snapshot = new SnapshotImpl();
        snapshot.setId(UUID.randomUUID().toString());
        snapshot.setName(Instant.now().toString());
        snapshot.setChain(chain);
        snapshot.setMaskedFields(chain.getMaskedFields());

        Collection<Element> rootElements = rootElements(chain.getElements());
        Map<String, String> idMap = createElementIdMap(rootElements);
        Collection<Element> elements = flatten(createElements(rootElements, idMap, null));
        Map<String, Element> elementMap = createElementMap(elements);

        snapshot.setElements(elements);
        snapshot.setConnections(createConnections(chain, idMap, elementMap));

        elements.forEach(element -> {
            ElementImpl elementImpl = (ElementImpl) element;
            elementImpl.setSnapshot(snapshot);
            elementImpl.setInputConnections(snapshot.getConnections()
                .stream()
                .filter(connection -> connection.getTo() == element)
                .toList());
            elementImpl.setOutputConnections(snapshot.getConnections()
                .stream()
                .filter(connection -> connection.getFrom() == element)
                .toList());
            elementImpl.getSwimlane().ifPresent(swimlane -> {
                elementImpl.setSwimlane(elementMap.get(idMap.get(swimlane.getId())));
            });
        });

        chain.getDefaultSwimlane().ifPresent(swimlane -> {
            snapshot.setDefaultSwimlane(elementMap.get(idMap.get(swimlane.getId())));
        });

        return snapshot;
    }

    private void forEachElement(Collection<Element> elements, Consumer<Element> consumer) {
        elements.forEach(element -> {
            consumer.accept(element);
            forEachElement(element.getChildren(), consumer);
        });
    }

    /**
     * The elements the chain owns directly. {@link Chain#getElements()} is flat: a container and the
     * elements it holds are both entries in it. Building from every entry would create each nested
     * element twice, once under its container and once more with no parent, and the two copies would
     * then compete for the chain's connections.
     */
    private static Collection<Element> rootElements(Collection<Element> elements) {
        return elements.stream().filter(element -> element.getParent().isEmpty()).toList();
    }

    /** Every element of the built tree, each exactly once, the shape {@link Snapshot#getElements()} returns. */
    private Collection<Element> flatten(Collection<Element> elements) {
        List<Element> result = new ArrayList<>();
        forEachElement(elements, result::add);
        return result;
    }

    private Map<String, String> createElementIdMap(Collection<Element> elements) {
        Map<String, String> idMap = new HashMap<>();
        forEachElement(elements, element -> idMap.put(element.getId(), UUID.randomUUID().toString()));
        return idMap;
    }

    /** Duplicate ids here would mean an element was built twice, so let {@code toMap} say so. */
    private Map<String, Element> createElementMap(Collection<Element> elements) {
        return elements.stream().collect(Collectors.toMap(Element::getId, element -> element));
    }

    private Collection<Element> createElements(
        Collection<Element> elements,
        Map<String, String> idMap,
        Element parent
    ) {
        return elements
            .stream()
            .<Element>map(element -> createElement(element, idMap, parent))
            .toList();
    }

    private ElementImpl createElement(Element element, Map<String, String> idMap, Element parent) {
        ElementImpl snapshotElement = new ElementImpl();

        snapshotElement.setId(idMap.get(element.getId()));
        snapshotElement.setOriginalId(element.getId());
        snapshotElement.setName(element.getName());
        snapshotElement.setDescription(element.getDescription());
        snapshotElement.setType(element.getType());
        snapshotElement.setProperties(element.getProperties());
        snapshotElement.setParent(parent);
        snapshotElement.setChildren(createElements(element.getChildren(), idMap, snapshotElement));
        snapshotElement.setChain(element.getChain());

        // Points at the source swimlane until build() remaps it to the snapshot element
        element.getSwimlane().ifPresent(snapshotElement::setSwimlane);

        ElementDescriptor elementDescriptor = elementDescriptorHelper.resolveDescriptor(element.getType());
        snapshotElement.setContainer(elementDescriptor.isContainer());
        snapshotElement.setSwimlaneElement(elementDescriptor.getType() == ElementType.SWIMLANE);

        Optional<ServiceEnvironment> environment = getServiceEnvironment(element);
        environment.ifPresent(snapshotElement::setServiceEnvironment);

        return snapshotElement;
    }

    private Collection<Connection> createConnections(
        Chain chain,
        Map<String, String> idMap,
        Map<String, Element> elementMap
    ) {
        return chain.getConnections()
            .stream()
            .<Connection>map(connection -> {
                Element from = elementMap.get(idMap.get(connection.getFrom().getId()));
                Element to = elementMap.get(idMap.get(connection.getTo().getId()));
                return new ConnectionImpl(from, to);
            })
            .toList();
    }

    private Optional<ServiceEnvironment> getServiceEnvironment(Element element) {
        return switch (element.getType()) {
            case SERVICE_CALL_COMPONENT,
                 ASYNC_API_TRIGGER_COMPONENT,
                 HTTP_TRIGGER_COMPONENT ->
                        Optional.ofNullable(element.getProperties())
                            .map(properties -> properties.get(SYSTEM_ID))
                            .map(Object::toString)
                            .map(this::getIntegrationServiceActiveEnvironment);
            default -> Optional.empty();
        };
    }

    private ServiceEnvironment getIntegrationServiceActiveEnvironment(String serviceId) {
        IntegrationService integrationService = integrationServiceCatalog.findById(serviceId)
            .orElseThrow(() -> new NoSuchElementException("Integration service not found: " + serviceId));
        return integrationService.getActiveEnvironment().orElseGet(() -> integrationService
            .getEnvironments()
            .stream()
            .findFirst()
            .orElseGet(() -> {
                ServiceEnvironmentImpl serviceEnvironment = new ServiceEnvironmentImpl();
                serviceEnvironment.setSystemId(serviceId);
                serviceEnvironment.setActivated(false);
                return serviceEnvironment;
            })
        );
    }

    private void verifyElementProperties(Chain chain) {
        elementPropertiesVerificationService.verifyElementProperties(chain);
    }
}

package org.qubership.integration.platform.maven.plugin.domain.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.chain.impl.ChainImpl;
import org.qubership.integration.platform.chain.impl.ConnectionImpl;
import org.qubership.integration.platform.chain.impl.ElementImpl;
import org.qubership.integration.platform.chain.impl.ServiceEnvironmentImpl;
import org.qubership.integration.platform.chain.model.Chain;
import org.qubership.integration.platform.chain.model.Connection;
import org.qubership.integration.platform.chain.model.Element;
import org.qubership.integration.platform.chain.model.IntegrationService;
import org.qubership.integration.platform.chain.model.ServiceEnvironment;
import org.qubership.integration.platform.chain.model.Snapshot;
import org.qubership.integration.platform.library.components.ElementDescriptorHelper;
import org.qubership.integration.platform.library.model.ElementDescriptor;
import org.qubership.integration.platform.library.model.ElementType;
import org.qubership.integration.platform.verification.ElementPropertiesVerificationService;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.qubership.integration.platform.library.constants.CamelNames.CONTAINER;
import static org.qubership.integration.platform.library.constants.CamelNames.SERVICE_CALL_COMPONENT;
import static org.qubership.integration.platform.library.constants.CamelOptions.SYSTEM_ID;

class SnapshotBuildServiceTest {

    private static final String SENDER = "sender";
    private static final String SWIMLANE = "swimlane";

    private final ElementDescriptorHelper elementDescriptorHelper = mock(ElementDescriptorHelper.class);
    private final ElementPropertiesVerificationService verificationService =
        mock(ElementPropertiesVerificationService.class);
    private final IntegrationServiceCatalogImpl catalog = new IntegrationServiceCatalogImpl();

    private SnapshotBuildService snapshotBuildService;

    @BeforeEach
    void setUp() {
        when(elementDescriptorHelper.resolveDescriptor(anyString())).thenReturn(descriptor(ElementType.MODULE, false));
        snapshotBuildService = new SnapshotBuildService(elementDescriptorHelper, verificationService, catalog);
    }

    @Test
    void assignsFreshElementIdsAndKeepsTheOriginalOnes() {
        ElementImpl child = element("child", SENDER);
        ElementImpl parent = element("parent", CONTAINER);
        parent.setChildren(List.of(child));
        Chain chain = chain(List.of(parent), List.of());

        Snapshot snapshot = snapshotBuildService.build(chain);

        Element snapshotParent = snapshot.getElements().iterator().next();
        Element snapshotChild = snapshotParent.getChildren().iterator().next();
        assertEquals("parent", snapshotParent.getOriginalId().orElseThrow());
        assertEquals("child", snapshotChild.getOriginalId().orElseThrow());
        assertNotEquals("parent", snapshotParent.getId());
        assertNotEquals("child", snapshotChild.getId());
        assertNotEquals(snapshotParent.getId(), snapshotChild.getId());
        assertSame(snapshotParent, snapshotChild.getParent().orElseThrow());
        assertSame(snapshot, snapshotChild.getSnapshot().orElseThrow());
    }

    @Test
    void marksContainersAndSwimlanesFromTheElementDescriptor() {
        when(elementDescriptorHelper.resolveDescriptor(CONTAINER)).thenReturn(descriptor(ElementType.CONTAINER, true));
        when(elementDescriptorHelper.resolveDescriptor(SWIMLANE)).thenReturn(descriptor(ElementType.SWIMLANE, true));
        Chain chain = chain(List.of(element("c1", CONTAINER), element("s1", SWIMLANE)), List.of());

        Snapshot snapshot = snapshotBuildService.build(chain);

        List<Element> elements = List.copyOf(snapshot.getElements());
        assertTrue(elements.get(0).isContainer());
        assertFalse(((ElementImpl) elements.get(0)).isSwimlane());
        assertTrue(((ElementImpl) elements.get(1)).isSwimlane());
    }

    @Test
    void rewiresConnectionsBetweenTheSnapshotElements() {
        ElementImpl from = element("from", SENDER);
        ElementImpl to = element("to", SENDER);
        Chain chain = chain(List.of(from, to), List.of(new ConnectionImpl(from, to)));

        Snapshot snapshot = snapshotBuildService.build(chain);

        List<Element> elements = List.copyOf(snapshot.getElements());
        Connection connection = snapshot.getConnections().iterator().next();
        assertSame(elements.get(0), connection.getFrom());
        assertSame(elements.get(1), connection.getTo());
        assertSame(connection, elements.get(0).getOutputConnections().iterator().next());
        assertSame(connection, elements.get(1).getInputConnections().iterator().next());
        assertTrue(elements.get(0).getInputConnections().isEmpty());
    }

    @Test
    void remapsTheDefaultSwimlaneToTheSnapshotElement() {
        ElementImpl swimlane = element("s1", SWIMLANE);
        ChainImpl chain = chain(List.of(swimlane), List.of());
        chain.setDefaultSwimlane(swimlane);

        Snapshot snapshot = snapshotBuildService.build(chain);

        assertSame(snapshot.getElements().iterator().next(), snapshot.getDefaultSwimlane().orElseThrow());
    }

    @Test
    void remapsTheSwimlaneOfEachElementToTheSnapshotElement() {
        ElementImpl swimlane = element("s1", SWIMLANE);
        ElementImpl member = element("e1", SENDER);
        member.setSwimlane(swimlane);
        Chain chain = chain(List.of(swimlane, member), List.of());

        Snapshot snapshot = snapshotBuildService.build(chain);

        List<Element> elements = List.copyOf(snapshot.getElements());
        assertSame(elements.get(0), elements.get(1).getSwimlane().orElseThrow());
    }

    @Test
    void attachesTheActiveEnvironmentOfTheCalledService() {
        catalog.addService(service("system-1", environment("env-1", true), environment("env-2", false)));
        Chain chain = chain(List.of(serviceCall("call", "system-1")), List.of());

        Snapshot snapshot = snapshotBuildService.build(chain);

        ServiceEnvironment environment = snapshot.getElements().iterator().next().getEnvironment().orElseThrow();
        assertEquals("env-1", environment.getId());
    }

    @Test
    void fallsBackToTheFirstEnvironmentWhenNoneIsActive() {
        catalog.addService(service("system-1", environment("env-2", false)));
        Chain chain = chain(List.of(serviceCall("call", "system-1")), List.of());

        Snapshot snapshot = snapshotBuildService.build(chain);

        ServiceEnvironment environment = snapshot.getElements().iterator().next().getEnvironment().orElseThrow();
        assertEquals("env-2", environment.getId());
    }

    @Test
    void synthesizesAnInactiveEnvironmentForAServiceWithoutOne() {
        catalog.addService(service("system-1"));
        Chain chain = chain(List.of(serviceCall("call", "system-1")), List.of());

        Snapshot snapshot = snapshotBuildService.build(chain);

        ServiceEnvironment environment = snapshot.getElements().iterator().next().getEnvironment().orElseThrow();
        assertEquals("system-1", environment.getSystemId());
        assertFalse(environment.isActivated());
    }

    @Test
    void failsWhenTheCalledServiceIsNotInTheCatalog() {
        Chain chain = chain(List.of(serviceCall("call", "system-1")), List.of());

        NoSuchElementException exception =
            assertThrows(NoSuchElementException.class, () -> snapshotBuildService.build(chain));

        assertTrue(exception.getMessage().contains("system-1"));
    }

    @Test
    void verifiesElementPropertiesAndLeavesPlainElementsWithoutAnEnvironment() {
        Chain chain = chain(List.of(element("e1", SENDER)), List.of());

        Snapshot snapshot = snapshotBuildService.build(chain);

        assertTrue(snapshot.getElements().iterator().next().getEnvironment().isEmpty());
        assertSame(chain, snapshot.getChain());
        verify(verificationService).verifyElementProperties(chain);
    }

    /**
     * ChainReader hands over a flat element list: a container and the elements it holds are both
     * entries in it, linked by parent and children. Building from every entry used to create each
     * nested element twice, and only one of the two copies ended up carrying the connections.
     */
    @Test
    void buildsEachElementOnceFromAFlatChain() {
        when(elementDescriptorHelper.resolveDescriptor(CONTAINER)).thenReturn(descriptor(ElementType.CONTAINER, true));
        ElementImpl child = element("child", SENDER);
        ElementImpl parent = element("parent", CONTAINER);
        parent.setChildren(List.of(child));
        child.setParent(parent);
        Chain chain = chain(List.of(parent, child), List.of(new ConnectionImpl(parent, child)));

        Snapshot snapshot = snapshotBuildService.build(chain);

        List<Element> elements = List.copyOf(snapshot.getElements());
        assertEquals(2, elements.size());
        assertEquals(2, elements.stream().map(Element::getId).distinct().count());

        Element snapshotParent = byOriginalId(elements, "parent");
        Element snapshotChild = byOriginalId(elements, "child");
        assertSame(snapshotChild, snapshotParent.getChildren().iterator().next());
        assertSame(snapshotParent, snapshotChild.getParent().orElseThrow());
    }

    /** The copy the container holds is the one the connections must reach, not a parentless twin. */
    @Test
    void wiresConnectionsToTheElementsTheContainerHolds() {
        when(elementDescriptorHelper.resolveDescriptor(CONTAINER)).thenReturn(descriptor(ElementType.CONTAINER, true));
        ElementImpl child = element("child", SENDER);
        ElementImpl parent = element("parent", CONTAINER);
        parent.setChildren(List.of(child));
        child.setParent(parent);
        Chain chain = chain(List.of(parent, child), List.of(new ConnectionImpl(parent, child)));

        Snapshot snapshot = snapshotBuildService.build(chain);

        Element snapshotParent = byOriginalId(List.copyOf(snapshot.getElements()), "parent");
        Element snapshotChild = snapshotParent.getChildren().iterator().next();
        assertEquals(1, snapshotChild.getInputConnections().size());
        assertSame(snapshotChild, snapshotChild.getInputConnections().iterator().next().getTo());
        assertSame(snapshotParent, snapshotChild.getInputConnections().iterator().next().getFrom());
    }

    private static Element byOriginalId(List<Element> elements, String originalId) {
        return elements.stream()
            .filter(element -> originalId.equals(element.getOriginalId().orElse(null)))
            .findFirst()
            .orElseThrow();
    }

    private static ElementDescriptor descriptor(ElementType type, boolean container) {
        ElementDescriptor descriptor = new ElementDescriptor();
        descriptor.setType(type);
        descriptor.setContainer(container);
        return descriptor;
    }

    private static ElementImpl element(String id, String type) {
        ElementImpl element = new ElementImpl();
        element.setId(id);
        element.setName(id);
        element.setType(type);
        return element;
    }

    private static ElementImpl serviceCall(String id, String systemId) {
        ElementImpl element = element(id, SERVICE_CALL_COMPONENT);
        element.setProperties(Map.of(SYSTEM_ID, systemId));
        return element;
    }

    private static ChainImpl chain(List<Element> elements, List<Connection> connections) {
        ChainImpl chain = new ChainImpl();
        chain.setId("chain-1");
        chain.setName("chain-1");
        chain.setElements(elements);
        chain.setConnections(connections);
        chain.setMaskedFields(List.of());
        return chain;
    }

    private static ServiceEnvironmentImpl environment(String id, boolean activated) {
        ServiceEnvironmentImpl environment = new ServiceEnvironmentImpl();
        environment.setId(id);
        environment.setActivated(activated);
        return environment;
    }

    private static IntegrationService service(String id, ServiceEnvironment... environments) {
        IntegrationService service = mock(IntegrationService.class);
        when(service.getId()).thenReturn(id);
        when(service.getEnvironments()).thenReturn(List.of(environments));
        when(service.getActiveEnvironment()).thenReturn(List.of(environments).stream()
            .filter(ServiceEnvironment::isActivated)
            .findFirst());
        return service;
    }
}

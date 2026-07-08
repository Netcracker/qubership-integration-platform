package org.qubership.integration.platform.runtime.catalog.adapters;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Dependency;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ContainerChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.SwimlaneChainElement;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChainElementAdapterTest {

    @DisplayName("Should pass through basic fields and report as a non-container with no children")
    @Test
    void shouldExposeBasicFields() {
        ChainElement element = ChainElement.builder()
                .id("e1")
                .name("Element 1")
                .description("some description")
                .type("http-sender")
                .originalId("orig-1")
                .properties(Map.of("key", "value"))
                .build();

        ChainElementAdapter adapter = new ChainElementAdapter(element);

        assertEquals("e1", adapter.getId());
        assertEquals("Element 1", adapter.getName());
        assertEquals("some description", adapter.getDescription());
        assertEquals("http-sender", adapter.getType());
        assertEquals("orig-1", adapter.getOriginalId().orElseThrow());
        assertEquals(Map.of("key", "value"), adapter.getProperties());
        assertFalse(adapter.isContainer());
        assertTrue(adapter.getChildren().isEmpty());
    }

    @DisplayName("Should return an empty originalId when absent")
    @Test
    void shouldReturnEmptyOriginalIdWhenAbsent() {
        ChainElement element = ChainElement.builder().id("e1").build();

        ChainElementAdapter adapter = new ChainElementAdapter(element);

        assertTrue(adapter.getOriginalId().isEmpty());
    }

    @DisplayName("Should report as a container and expose wrapped children for a ContainerChainElement")
    @Test
    void shouldReportContainerAndExposeChildren() {
        ContainerChainElement container = ContainerChainElement.builder().id("c1").name("Container").build();
        ChainElement child = ChainElement.builder().id("child-1").name("Child").build();
        container.addChildElement(child);

        ChainElementAdapter adapter = new ChainElementAdapter(container);

        assertTrue(adapter.isContainer());
        assertEquals(1, adapter.getChildren().size());
        assertEquals("child-1", adapter.getChildren().iterator().next().getId());
    }

    @DisplayName("Should wrap the parent element when present")
    @Test
    void shouldWrapParentWhenPresent() {
        ContainerChainElement parent = ContainerChainElement.builder().id("parent-1").build();
        ChainElement element = ChainElement.builder().id("e1").parent(parent).build();

        ChainElementAdapter adapter = new ChainElementAdapter(element);

        assertTrue(adapter.getParent().isPresent());
        assertEquals("parent-1", adapter.getParent().get().getId());
    }

    @DisplayName("Should return an empty parent when absent")
    @Test
    void shouldReturnEmptyParentWhenAbsent() {
        ChainElement element = ChainElement.builder().id("e1").build();

        ChainElementAdapter adapter = new ChainElementAdapter(element);

        assertTrue(adapter.getParent().isEmpty());
    }

    @DisplayName("Should wrap the swimlane element when present")
    @Test
    void shouldWrapSwimlaneWhenPresent() {
        SwimlaneChainElement swimlane = SwimlaneChainElement.builder().id("swimlane-1").name("Swimlane").build();
        ChainElement element = ChainElement.builder().id("e1").swimlane(swimlane).build();

        ChainElementAdapter adapter = new ChainElementAdapter(element);

        assertTrue(adapter.getSwimlane().isPresent());
        assertEquals("swimlane-1", adapter.getSwimlane().get().getId());
    }

    @DisplayName("Should return an empty swimlane when absent")
    @Test
    void shouldReturnEmptySwimlaneWhenAbsent() {
        ChainElement element = ChainElement.builder().id("e1").build();

        ChainElementAdapter adapter = new ChainElementAdapter(element);

        assertTrue(adapter.getSwimlane().isEmpty());
    }

    @DisplayName("Should wrap the service environment when present")
    @Test
    void shouldWrapServiceEnvironmentWhenPresent() {
        org.qubership.integration.platform.runtime.catalog.model.system.ServiceEnvironment environment =
                new org.qubership.integration.platform.runtime.catalog.model.system.ServiceEnvironment();
        environment.setId("env-1");
        ChainElement element = ChainElement.builder().id("e1").environment(environment).build();

        ChainElementAdapter adapter = new ChainElementAdapter(element);

        assertTrue(adapter.getEnvironment().isPresent());
        assertEquals("env-1", adapter.getEnvironment().get().getId());
    }

    @DisplayName("Should return an empty environment when absent")
    @Test
    void shouldReturnEmptyEnvironmentWhenAbsent() {
        ChainElement element = ChainElement.builder().id("e1").build();

        ChainElementAdapter adapter = new ChainElementAdapter(element);

        assertTrue(adapter.getEnvironment().isEmpty());
    }

    @DisplayName("Should wrap input and output connections")
    @Test
    void shouldWrapInputAndOutputConnections() {
        ChainElement from = ChainElement.builder().id("e1").build();
        ChainElement to = ChainElement.builder().id("e2").build();
        Dependency dependency = Dependency.of(from, to);
        from.setOutputDependencies(List.of(dependency));
        to.setInputDependencies(List.of(dependency));

        ChainElementAdapter fromAdapter = new ChainElementAdapter(from);
        ChainElementAdapter toAdapter = new ChainElementAdapter(to);

        assertEquals(1, fromAdapter.getOutputConnections().size());
        assertTrue(fromAdapter.getInputConnections().isEmpty());
        assertEquals(1, toAdapter.getInputConnections().size());
        assertTrue(toAdapter.getOutputConnections().isEmpty());
    }

    @DisplayName("Should wrap the owning chain")
    @Test
    void shouldWrapChain() {
        Chain chain = Chain.builder().id("chain-1").name("Chain 1").build();
        ChainElement element = ChainElement.builder().id("e1").chain(chain).build();

        ChainElementAdapter adapter = new ChainElementAdapter(element);

        assertEquals("chain-1", adapter.getChain().getId());
        assertEquals("Chain 1", adapter.getChain().getName());
    }

    @DisplayName("Should wrap the owning snapshot when present")
    @Test
    void shouldWrapSnapshotWhenPresent() {
        org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot snapshot =
                org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot.builder()
                        .id("snap-1")
                        .build();
        ChainElement element = ChainElement.builder().id("e1").snapshot(snapshot).build();

        ChainElementAdapter adapter = new ChainElementAdapter(element);

        assertTrue(adapter.getSnapshot().isPresent());
        assertEquals("snap-1", adapter.getSnapshot().get().getId());
    }
}

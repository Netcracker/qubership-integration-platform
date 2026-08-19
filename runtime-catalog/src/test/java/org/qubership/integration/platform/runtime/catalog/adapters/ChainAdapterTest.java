package org.qubership.integration.platform.runtime.catalog.adapters;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.chain.model.Element;
import org.qubership.integration.platform.chain.model.Label;
import org.qubership.integration.platform.chain.model.MaskedField;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.ChainLabel;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Dependency;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Folder;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.SwimlaneChainElement;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ChainAdapterTest {

    @DisplayName("Should pass through basic and descriptive fields")
    @Test
    void shouldExposeBasicFields() {
        Chain chain = Chain.builder()
                .id("chain-1")
                .name("Chain 1")
                .description("some description")
                .businessDescription("business description")
                .assumptions("some assumptions")
                .outOfScope("some out of scope")
                .build();

        ChainAdapter adapter = new ChainAdapter(chain);

        assertEquals("chain-1", adapter.getId());
        assertEquals("Chain 1", adapter.getName());
        assertEquals("some description", adapter.getDescription());
        assertEquals("business description", adapter.getBusinessDescription());
        assertEquals("some assumptions", adapter.getAssumptions());
        assertEquals("some out of scope", adapter.getOutOfScope());
    }

    @DisplayName("Should wrap all elements")
    @Test
    void shouldWrapElements() {
        Chain chain = Chain.builder().id("chain-1").build();
        chain.addElement(ChainElement.builder().id("e1").build());
        chain.addElement(ChainElement.builder().id("e2").build());

        ChainAdapter adapter = new ChainAdapter(chain);

        assertEquals(2, adapter.getElements().size());
        assertTrue(adapter.getElements().stream().map(Element::getId).toList().containsAll(List.of("e1", "e2")));
    }

    @DisplayName("Should wrap dependencies derived from element input/output dependencies as connections")
    @Test
    void shouldWrapConnections() {
        Chain chain = Chain.builder().id("chain-1").build();
        ChainElement from = ChainElement.builder().id("e1").build();
        ChainElement to = ChainElement.builder().id("e2").build();
        Dependency dependency = Dependency.of(from, to);
        from.setOutputDependencies(List.of(dependency));
        to.setInputDependencies(List.of(dependency));
        chain.addElement(from);
        chain.addElement(to);

        ChainAdapter adapter = new ChainAdapter(chain);

        assertEquals(1, adapter.getConnections().size());
        assertEquals("e1", adapter.getConnections().iterator().next().getFrom().getId());
        assertEquals("e2", adapter.getConnections().iterator().next().getTo().getId());
    }

    @DisplayName("Should wrap labels")
    @Test
    void shouldWrapLabels() {
        Chain chain = Chain.builder().id("chain-1").build();
        chain.addLabels(Set.of(new ChainLabel("label-a", chain), new ChainLabel("label-b", chain)));

        ChainAdapter adapter = new ChainAdapter(chain);

        assertEquals(2, adapter.getLabels().size());
        assertTrue(adapter.getLabels().stream().map(Label::getName).toList().containsAll(List.of("label-a", "label-b")));
    }

    @DisplayName("Should wrap masked fields")
    @Test
    void shouldWrapMaskedFields() {
        Chain chain = Chain.builder().id("chain-1").build();
        chain.addMaskedField(org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.MaskedField.builder()
                .id("mf-1")
                .name("secret")
                .build());

        ChainAdapter adapter = new ChainAdapter(chain);

        assertEquals(1, adapter.getMaskedFields().size());
        assertEquals("secret", adapter.getMaskedFields().stream().findFirst().map(MaskedField::getName).orElseThrow());
    }

    @DisplayName("Should wrap the default and reuse swimlanes when present")
    @Test
    void shouldWrapSwimlanesWhenPresent() {
        Chain chain = Chain.builder().id("chain-1").build();
        SwimlaneChainElement defaultSwimlane = SwimlaneChainElement.builder().id("default-swimlane").build();
        SwimlaneChainElement reuseSwimlane = SwimlaneChainElement.builder().id("reuse-swimlane").build();
        chain.setDefaultSwimlane(defaultSwimlane);
        chain.setReuseSwimlane(reuseSwimlane);

        ChainAdapter adapter = new ChainAdapter(chain);

        assertEquals("default-swimlane", adapter.getDefaultSwimlane().orElseThrow().getId());
        assertEquals("reuse-swimlane", adapter.getReuseSwimlane().orElseThrow().getId());
    }

    @DisplayName("Should return empty swimlanes when absent")
    @Test
    void shouldReturnEmptySwimlanesWhenAbsent() {
        Chain chain = Chain.builder().id("chain-1").build();

        ChainAdapter adapter = new ChainAdapter(chain);

        assertTrue(adapter.getDefaultSwimlane().isEmpty());
        assertTrue(adapter.getReuseSwimlane().isEmpty());
    }

    @DisplayName("Should wrap the parent folder when present")
    @Test
    void shouldWrapParentFolderWhenPresent() {
        Folder folder = Folder.builder().id("folder-1").name("My Folder").build();
        Chain chain = Chain.builder().id("chain-1").parentFolder(folder).build();

        ChainAdapter adapter = new ChainAdapter(chain);

        assertTrue(adapter.getParentFolder().isPresent());
        assertEquals("folder-1", adapter.getParentFolder().get().getId());
    }

    @DisplayName("Should return an empty parent folder when absent")
    @Test
    void shouldReturnEmptyParentFolderWhenAbsent() {
        Chain chain = Chain.builder().id("chain-1").build();

        ChainAdapter adapter = new ChainAdapter(chain);

        assertTrue(adapter.getParentFolder().isEmpty());
    }
}

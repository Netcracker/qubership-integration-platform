package org.qubership.integration.platform.runtime.catalog.adapters;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.chain.model.Element;
import org.qubership.integration.platform.chain.model.Label;
import org.qubership.integration.platform.chain.model.MaskedField;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Dependency;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Folder;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.SnapshotLabel;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.SwimlaneChainElement;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotAdapterTest {

    @DisplayName("Should pass through basic fields and wrap the owning chain")
    @Test
    void shouldExposeBasicFieldsAndWrapChain() {
        Chain chain = Chain.builder().id("chain-1").name("Chain 1").build();
        Snapshot snapshot = Snapshot.builder().id("snap-1").name("Snapshot 1").description("some description").chain(chain).build();

        SnapshotAdapter adapter = new SnapshotAdapter(snapshot);

        assertEquals("snap-1", adapter.getId());
        assertEquals("Snapshot 1", adapter.getName());
        assertEquals("some description", adapter.getDescription());
        assertEquals("chain-1", adapter.getChain().getId());
    }

    @DisplayName("Should always report empty businessDescription, assumptions and outOfScope, since a Snapshot does not carry them")
    @Test
    void shouldAlwaysReportEmptyDescriptiveFields() {
        Chain chain = Chain.builder().id("chain-1").businessDescription("chain business description").build();
        Snapshot snapshot = Snapshot.builder().id("snap-1").chain(chain).build();

        SnapshotAdapter adapter = new SnapshotAdapter(snapshot);

        assertEquals("", adapter.getBusinessDescription());
        assertEquals("", adapter.getAssumptions());
        assertEquals("", adapter.getOutOfScope());
    }

    @DisplayName("Should wrap the snapshot's own elements, not the chain's")
    @Test
    void shouldWrapOwnElements() {
        Chain chain = Chain.builder().id("chain-1").build();
        Snapshot snapshot = Snapshot.builder().id("snap-1").chain(chain).build();
        snapshot.addElement(ChainElement.builder().id("e1").build());

        SnapshotAdapter adapter = new SnapshotAdapter(snapshot);

        assertEquals(1, adapter.getElements().size());
        assertEquals("e1", adapter.getElements().stream().findFirst().map(Element::getId).orElseThrow());
    }

    @DisplayName("Should wrap dependencies derived from its own elements' input/output dependencies as connections")
    @Test
    void shouldWrapConnections() {
        Chain chain = Chain.builder().id("chain-1").build();
        Snapshot snapshot = Snapshot.builder().id("snap-1").chain(chain).build();
        ChainElement from = ChainElement.builder().id("e1").build();
        ChainElement to = ChainElement.builder().id("e2").build();
        Dependency dependency = Dependency.of(from, to);
        from.setOutputDependencies(List.of(dependency));
        to.setInputDependencies(List.of(dependency));
        snapshot.addElement(from);
        snapshot.addElement(to);

        SnapshotAdapter adapter = new SnapshotAdapter(snapshot);

        assertEquals(1, adapter.getConnections().size());
    }

    @DisplayName("Should wrap labels")
    @Test
    void shouldWrapLabels() {
        Chain chain = Chain.builder().id("chain-1").build();
        Snapshot snapshot = Snapshot.builder().id("snap-1").chain(chain).build();
        snapshot.addLabels(Set.of(new SnapshotLabel("label-a", snapshot), new SnapshotLabel("label-b", snapshot)));

        SnapshotAdapter adapter = new SnapshotAdapter(snapshot);

        assertEquals(2, adapter.getLabels().size());
        assertTrue(adapter.getLabels().stream().map(Label::getName).toList().containsAll(List.of("label-a", "label-b")));
    }

    @DisplayName("Should wrap masked fields")
    @Test
    void shouldWrapMaskedFields() {
        Chain chain = Chain.builder().id("chain-1").build();
        Snapshot snapshot = Snapshot.builder().id("snap-1").chain(chain).build();
        snapshot.addMaskedField(org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.MaskedField.builder()
                .id("mf-1")
                .name("secret")
                .build());

        SnapshotAdapter adapter = new SnapshotAdapter(snapshot);

        assertEquals(1, adapter.getMaskedFields().size());
        assertEquals("secret", adapter.getMaskedFields().stream().findFirst().map(MaskedField::getName).orElseThrow());
    }

    @DisplayName("Should wrap its own default and reuse swimlanes when present")
    @Test
    void shouldWrapSwimlanesWhenPresent() {
        Chain chain = Chain.builder().id("chain-1").build();
        Snapshot snapshot = Snapshot.builder()
                .id("snap-1")
                .chain(chain)
                .defaultSwimlane(SwimlaneChainElement.builder().id("default-swimlane").build())
                .reuseSwimlane(SwimlaneChainElement.builder().id("reuse-swimlane").build())
                .build();

        SnapshotAdapter adapter = new SnapshotAdapter(snapshot);

        assertEquals("default-swimlane", adapter.getDefaultSwimlane().orElseThrow().getId());
        assertEquals("reuse-swimlane", adapter.getReuseSwimlane().orElseThrow().getId());
    }

    @DisplayName("Should delegate the parent folder to the owning chain's parent folder")
    @Test
    void shouldDelegateParentFolderToChain() {
        Folder folder = Folder.builder().id("folder-1").name("My Folder").build();
        Chain chain = Chain.builder().id("chain-1").parentFolder(folder).build();
        Snapshot snapshot = Snapshot.builder().id("snap-1").chain(chain).build();

        SnapshotAdapter adapter = new SnapshotAdapter(snapshot);

        assertTrue(adapter.getParentFolder().isPresent());
        assertEquals("folder-1", adapter.getParentFolder().get().getId());
    }

    @DisplayName("Should return an empty parent folder when the owning chain has none")
    @Test
    void shouldReturnEmptyParentFolderWhenChainHasNone() {
        Chain chain = Chain.builder().id("chain-1").build();
        Snapshot snapshot = Snapshot.builder().id("snap-1").chain(chain).build();

        SnapshotAdapter adapter = new SnapshotAdapter(snapshot);

        assertTrue(adapter.getParentFolder().isEmpty());
    }
}

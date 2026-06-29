package org.qubership.integration.platform.runtime.catalog.adapters;

import org.qubership.integration.platform.chain.impl.LabelImpl;
import org.qubership.integration.platform.chain.model.*;

import java.util.Collection;
import java.util.Collections;
import java.util.Optional;

public class SnapshotAdapter implements Snapshot {
    private final org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot snapshot;

    public SnapshotAdapter(org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot snapshot) {
        this.snapshot = snapshot;
    }

    @Override
    public Chain getChain() {
        return new ChainAdapter(snapshot.getChain());
    }

    @Override
    public String getBusinessDescription() {
        return "";
    }

    @Override
    public String getAssumptions() {
        return "";
    }

    @Override
    public String getOutOfScope() {
        return "";
    }

    @Override
    public Collection<Element> getElements() {
        return Optional.ofNullable(snapshot.getElements())
            .orElse(Collections.emptyList())
            .stream()
            .<Element>map(ChainElementAdapter::new)
            .toList();
    }

    @Override
    public Collection<Connection> getConnections() {
        return Optional.ofNullable(snapshot.getDependencies())
            .orElse(Collections.emptySet())
            .stream()
            .<Connection>map(DependencyAdapter::new)
            .toList();
    }

    @Override
    public Collection<Label> getLabels() {
        return snapshot.getLabels().stream()
            .<Label>map(l -> new LabelImpl(l.getName(), l.isTechnical()))
            .toList();
    }

    @Override
    public Optional<Element> getDefaultSwimlane() {
        return Optional.ofNullable(snapshot.getDefaultSwimlane())
            .map(ChainElementAdapter::new);
    }

    @Override
    public Optional<Element> getReuseSwimlane() {
        return Optional.ofNullable(snapshot.getReuseSwimlane())
            .map(ChainElementAdapter::new);
    }

    @Override
    public Collection<MaskedField> getMaskedFields() {
        return snapshot.getMaskedFields().stream().<MaskedField>map(MaskedFieldAdapter::new).toList();
    }

    @Override
    public String getId() {
        return snapshot.getId();
    }

    @Override
    public String getName() {
        return snapshot.getName();
    }

    @Override
    public String getDescription() {
        return snapshot.getDescription();
    }
}

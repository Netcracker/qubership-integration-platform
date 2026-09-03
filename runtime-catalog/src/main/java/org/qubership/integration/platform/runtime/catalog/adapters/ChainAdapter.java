package org.qubership.integration.platform.runtime.catalog.adapters;

import org.qubership.integration.platform.chain.impl.LabelImpl;
import org.qubership.integration.platform.chain.model.*;

import java.util.Collection;
import java.util.Collections;
import java.util.Optional;

public class ChainAdapter implements Chain {
    private final org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain chain;

    public ChainAdapter(org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain chain) {
        this.chain = chain;
    }

    @Override
    public String getBusinessDescription() {
        return chain.getBusinessDescription();
    }

    @Override
    public String getAssumptions() {
        return chain.getAssumptions();
    }

    @Override
    public String getOutOfScope() {
        return chain.getOutOfScope();
    }

    @Override
    public Collection<Element> getElements() {
        return Optional.ofNullable(chain.getElements())
            .orElse(Collections.emptyList())
            .stream()
            .<Element>map(ChainElementAdapter::new)
            .toList();
    }

    @Override
    public Collection<Connection> getConnections() {
        return Optional.ofNullable(chain.getDependencies())
            .orElse(Collections.emptySet())
            .stream()
            .<Connection>map(DependencyAdapter::new)
            .toList();
    }

    @Override
    public Collection<Label> getLabels() {
        return chain.getLabels().stream()
            .<Label>map(l -> new LabelImpl(l.getName(), l.isTechnical()))
            .toList();
    }

    @Override
    public Optional<Element> getDefaultSwimlane() {
        return Optional.ofNullable(chain.getDefaultSwimlane())
            .map(ChainElementAdapter::new);
    }

    @Override
    public Optional<Element> getReuseSwimlane() {
        return Optional.ofNullable(chain.getReuseSwimlane())
            .map(ChainElementAdapter::new);
    }

    @Override
    public Collection<MaskedField> getMaskedFields() {
        return chain.getMaskedFields().stream().<MaskedField>map(MaskedFieldAdapter::new).toList();
    }

    @Override
    public String getId() {
        return chain.getId();
    }

    @Override
    public String getName() {
        return chain.getName();
    }

    @Override
    public String getDescription() {
        return chain.getDescription();
    }

    @Override
    public Optional<Folder> getParentFolder() {
        return Optional.ofNullable(chain.getParentFolder())
            .map(FolderAdapter::new);
    }
}

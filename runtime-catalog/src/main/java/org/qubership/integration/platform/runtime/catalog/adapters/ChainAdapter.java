package org.qubership.integration.platform.runtime.catalog.adapters;

import org.qubership.integration.platform.chain.model.Chain;
import org.qubership.integration.platform.chain.model.Connection;
import org.qubership.integration.platform.chain.model.Element;
import org.qubership.integration.platform.chain.model.Label;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
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
        return Optional.ofNullable(chain.getElements()).orElse(Collections.emptyList())
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
        return List.of();
    }

    @Override
    public Optional<Element> getDefaultSwimlane() {
        return Optional.empty();
    }

    @Override
    public Optional<Element> getReuseSwimlane() {
        return Optional.empty();
    }

    @Override
    public String getId() {
        return "";
    }

    @Override
    public String getName() {
        return "";
    }

    @Override
    public String getDescription() {
        return "";
    }
}

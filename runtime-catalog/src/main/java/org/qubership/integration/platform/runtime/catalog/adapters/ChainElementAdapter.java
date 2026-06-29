package org.qubership.integration.platform.runtime.catalog.adapters;

import org.qubership.integration.platform.chain.model.*;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ContainerChainElement;

import java.util.*;

public class ChainElementAdapter implements Element {
    private final ChainElement chainElement;

    public ChainElementAdapter(ChainElement chainElement) {
        this.chainElement = chainElement;
    }

    @Override
    public String getType() {
        return chainElement.getType();
    }

    @Override
    public Optional<Element> getParent() {
        return Optional.ofNullable(chainElement.getParent()).map(ChainElementAdapter::new);
    }

    @Override
    public Optional<Snapshot> getSnapshot() {
        return Optional.ofNullable(chainElement.getSnapshot()).map(SnapshotAdapter::new);
    }

    @Override
    public Map<String, Object> getProperties() {
        return chainElement.getProperties();
    }

    @Override
    public Collection<Element> getChildren() {
        return chainElement instanceof ContainerChainElement container
            ? Optional.ofNullable(container.getElements())
                .orElse(Collections.emptyList())
                .stream()
                .<Element>map(ChainElementAdapter::new)
                .toList()
            : Collections.emptyList();
    }

    @Override
    public Collection<Connection> getInputConnections() {
        return Optional.ofNullable(chainElement.getInputDependencies())
            .orElse(Collections.emptyList())
            .stream()
            .<Connection>map(DependencyAdapter::new)
            .toList();
    }

    @Override
    public Collection<Connection> getOutputConnections() {
        return Optional.ofNullable(chainElement.getOutputDependencies())
            .orElse(Collections.emptyList())
            .stream()
            .<Connection>map(DependencyAdapter::new)
            .toList();
    }

    @Override
    public Optional<String> getOriginalId() {
        return Optional.ofNullable(chainElement.getOriginalId());
    }

    @Override
    public Chain getChain() {
        return new ChainAdapter(chainElement.getChain());
    }

    @Override
    public Optional<ServiceEnvironment> getEnvironment() {
        return Optional.ofNullable(chainElement.getEnvironment()).map(ServiceEnvironmentAdapter::new);
    }

    @Override
    public boolean isContainer() {
        return chainElement instanceof ContainerChainElement;
    }

    @Override
    public String getId() {
        return chainElement.getId();
    }

    @Override
    public String getName() {
        return chainElement.getName();
    }

    @Override
    public String getDescription() {
        return chainElement.getDescription();
    }
}

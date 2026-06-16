package org.qubership.integration.platform.runtime.catalog.adapters;

import org.qubership.integration.platform.chain.model.Connection;
import org.qubership.integration.platform.chain.model.Element;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Dependency;

public class DependencyAdapter implements Connection {
    private final Dependency dependency;

    public DependencyAdapter(final Dependency dependency) {
        this.dependency = dependency;
    }

    @Override
    public Element getFrom() {
        return new ChainElementAdapter(dependency.getElementFrom());
    }

    @Override
    public Element getTo() {
        return new ChainElementAdapter(dependency.getElementTo());
    }
}

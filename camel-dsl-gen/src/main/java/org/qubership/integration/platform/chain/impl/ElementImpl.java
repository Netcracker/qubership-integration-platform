package org.qubership.integration.platform.chain.impl;

import lombok.Setter;
import org.qubership.integration.platform.chain.model.*;

import java.util.*;

public class ElementImpl implements Element {
    @Setter
    private String id;
    @Setter
    private String originalId;
    @Setter
    private String name;
    @Setter
    private String description;
    @Setter
    private String type;
    @Setter
    private Element parent;
    @Setter
    private Map<String, Object> properties = new HashMap<>();
    @Setter
    private Collection<Element> children = new ArrayList<>();
    @Setter
    private Collection<Connection> inputConnections = new ArrayList<>();
    @Setter
    private Collection<Connection> outputConnections = new ArrayList<>();
    @Setter
    private Chain chain;
    @Setter
    private ServiceEnvironment serviceEnvironment;
    @Setter
    private boolean container;
    @Setter
    private Snapshot snapshot;

    @Override
    public String getType() {
        return type;
    }

    @Override
    public Optional<Element> getParent() {
        return Optional.ofNullable(parent);
    }

    @Override
    public Map<String, Object> getProperties() {
        return properties;
    }

    @Override
    public Collection<Element> getChildren() {
        return children;
    }

    @Override
    public Collection<Connection> getInputConnections() {
        return inputConnections;
    }

    @Override
    public Collection<Connection> getOutputConnections() {
        return outputConnections;
    }

    @Override
    public Optional<String> getOriginalId() {
        return Optional.ofNullable(originalId);
    }

    @Override
    public Chain getChain() {
        return chain;
    }

    @Override
    public Optional<ServiceEnvironment> getEnvironment() {
        return Optional.ofNullable(serviceEnvironment);
    }

    @Override
    public boolean isContainer() {
        return container;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public Optional<Snapshot> getSnapshot() {
        return Optional.ofNullable(snapshot);
    }
}

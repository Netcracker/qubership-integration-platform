package org.qubership.integration.platform.chain.model;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

public interface Element extends Entity {
    String getType();

    Optional<Element> getParent();

    Map<String, Object> getProperties();

    Collection<Element> getChildren();

    Collection<Connection> getInputConnections();

    Collection<Connection> getOutputConnections();
}

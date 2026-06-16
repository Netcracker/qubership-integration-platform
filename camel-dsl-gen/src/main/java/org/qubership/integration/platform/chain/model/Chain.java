package org.qubership.integration.platform.chain.model;

import java.util.Collection;
import java.util.Optional;

public interface Chain extends Entity {
    String getBusinessDescription();

    String getAssumptions();

    String getOutOfScope();

    Collection<Element> getElements();

    Collection<Connection> getConnections();

    Collection<Label> getLabels();

    Optional<Element> getDefaultSwimlane();

    Optional<Element> getReuseSwimlane();
}

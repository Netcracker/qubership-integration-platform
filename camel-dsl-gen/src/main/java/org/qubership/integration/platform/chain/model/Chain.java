package org.qubership.integration.platform.chain.model;

import java.util.Collection;

public interface Chain extends Entity {
    String getBusinessDescription();

    String getAssumptions();

    String getOutOfScope();

    Collection<Element> getElements();

    Collection<Label> getLabels();
}

package org.qubership.integration.platform.serdes.model.data;

import java.util.Collection;

public interface Chain extends Entity {
    String getBusinessDescription();

    String getAssumptions();

    String getOutOfScope();

    Collection<Element> getElements();

    Collection<Label> getLabels();
}

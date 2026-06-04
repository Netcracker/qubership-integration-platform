package org.qubership.integration.platform.serdes.model.data;

import java.util.Map;
import java.util.Optional;

public interface Element extends Entity {
    String getType();

    Optional<Element> getParent();

    Map<String, Object> getProperties();
}

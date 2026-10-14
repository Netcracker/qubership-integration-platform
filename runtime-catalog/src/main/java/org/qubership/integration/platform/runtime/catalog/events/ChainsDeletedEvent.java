package org.qubership.integration.platform.runtime.catalog.events;

import java.util.Collection;

public record ChainsDeletedEvent(Collection<String> chainIds) {
}

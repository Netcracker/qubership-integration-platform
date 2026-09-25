package org.qubership.integration.platform.camelk.locations;

import org.qubership.integration.platform.camelk.model.ResourceBuildContext;
import org.qubership.integration.platform.chain.model.Snapshot;

import java.util.function.Function;

public interface SourceLocationGetter extends Function<ResourceBuildContext<Snapshot>, String> {
}

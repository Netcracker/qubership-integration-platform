package org.qubership.integration.platform.camelk.model;

public interface ResourceBuilder<T> {
    boolean enabled(ResourceBuildContext<T> context);

    String build(ResourceBuildContext<T> context) throws Exception;
}

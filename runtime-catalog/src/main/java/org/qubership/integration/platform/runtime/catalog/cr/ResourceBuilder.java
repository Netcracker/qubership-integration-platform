package org.qubership.integration.platform.runtime.catalog.cr;

public interface ResourceBuilder<T> {
    boolean enabled(ResourceBuildContext<T> context);

    String build(ResourceBuildContext<T> context) throws Exception;
}

package org.qubership.integration.platform.runtime.catalog.cr.naming;

public interface NamingStrategy<T> {
    String getName(T context);
}

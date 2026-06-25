package org.qubership.integration.platform.camelk.naming;

public interface NamingStrategy<T> {
    String getName(T context);
}

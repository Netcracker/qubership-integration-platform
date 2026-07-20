package org.qubership.integration.platform.camelk.naming.strategies;

import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.camelk.naming.NamingStrategy;
import org.qubership.integration.platform.camelk.naming.validation.K8sNameVerifier;

@Slf4j
public abstract class K8sResourceNamingStrategy<T> implements NamingStrategy<T> {
    private final K8sNameVerifier nameVerifier;

    public K8sResourceNamingStrategy(K8sNameVerifier nameVerifier) {
        this.nameVerifier = nameVerifier;
    }

    @Override
    public String getName(T context) {
        String name = proposeName(context);
        nameVerifier.verify(name);
        return name;
    }

    protected abstract String proposeName(T context);
}

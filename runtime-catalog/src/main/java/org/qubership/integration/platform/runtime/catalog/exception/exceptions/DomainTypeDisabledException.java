package org.qubership.integration.platform.runtime.catalog.exception.exceptions;

import org.qubership.integration.platform.runtime.catalog.model.domains.DomainType;

public class DomainTypeDisabledException extends RuntimeException {
    public DomainTypeDisabledException(DomainType domainType) {
        super(buildMessage(domainType));
    }

    private static String buildMessage(DomainType domainType) {
        return String.format("Domain type %s is disabled", domainType);
    }
}

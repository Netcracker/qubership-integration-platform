package org.qubership.integration.platform.runtime.catalog.exception.exceptions;

import lombok.Getter;
import org.qubership.integration.platform.runtime.catalog.model.domains.DomainType;

public class DomainTypeDisabledException extends RuntimeException {
    @Getter
    private final DomainType domainType;

    public DomainTypeDisabledException(DomainType domainType) {
        super(buildMessage(domainType));
        this.domainType = domainType;
    }

    private static String buildMessage(DomainType domainType) {
        return String.format("Domain type %s is disabled", domainType);
    }
}

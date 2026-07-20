package org.qubership.integration.platform.engine.configuration.security;

import java.util.function.Supplier;

public interface TokenSupplierProvider {

    Supplier<String> tokenSupplier();
}

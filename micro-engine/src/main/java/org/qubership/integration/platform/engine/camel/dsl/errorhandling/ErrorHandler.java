package org.qubership.integration.platform.engine.camel.dsl.errorhandling;

import org.apache.camel.CamelContext;

public interface ErrorHandler {
    void handleError(CamelContext context, Exception exception) throws Exception;
}

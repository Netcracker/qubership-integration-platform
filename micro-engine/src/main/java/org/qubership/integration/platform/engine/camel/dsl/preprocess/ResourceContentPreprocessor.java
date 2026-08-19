package org.qubership.integration.platform.engine.camel.dsl.preprocess;

public interface ResourceContentPreprocessor {
    String apply(String content) throws Exception;

    /**
     * Whether this preprocessor is safe to run during {@code preParseRoute}, before any beans are registered
     * in the Camel registry. Only preprocessors that do not read the registry qualify; MaaS and route-variable
     * resolvers do, so they stay in the route-loading phase and default to {@code false}.
     */
    default boolean runsInPreParse() {
        return false;
    }
}

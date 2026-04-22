package org.qubership.integration.platform.engine.camel.dsl.preprocess;

public interface ResourceContentPreprocessor {
    String apply(String content) throws Exception;
}

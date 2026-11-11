package org.qubership.integration.platform.engine.camel.predeploy;

import org.apache.camel.Endpoint;

public interface PredeployCheck {
    boolean applicableTo(String scheme);

    void check(Endpoint endpoint, boolean consumer) throws Exception;
}

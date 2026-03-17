package org.qubership.integration.platform.runtime.catalog.model.system.asyncapi;

public enum AsyncApiVersion {
    V2, V3;

    public static AsyncApiVersion detect(String asyncapiField) {
        if (asyncapiField != null && asyncapiField.startsWith("3.")) {
            return V3;
        }
        return V2;
    }
}

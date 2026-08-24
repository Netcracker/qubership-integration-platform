package org.qubership.integration.platform.chain.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Environment type")
public enum EnvironmentSourceType {
    MANUAL,
    @Deprecated
    MAAS, // maas with instanceId and classifier
    MAAS_BY_CLASSIFIER // maas only with classifier
}

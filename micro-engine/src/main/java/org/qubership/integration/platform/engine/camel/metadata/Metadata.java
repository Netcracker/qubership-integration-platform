package org.qubership.integration.platform.engine.camel.metadata;

import lombok.*;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Metadata {
    DeploymentInfo deploymentInfo;
}

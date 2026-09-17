package org.qubership.integration.platform.camelk.model.options;

import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContainerOptions {
    private String image;

    @Builder.Default
    private ImagePullPolicy imagePullPolicy = ImagePullPolicy.IfNotPresent;

    @Builder.Default
    private Limits request = new Limits();

    @Builder.Default
    private Limits limit = new Limits();

    @Builder.Default
    private boolean readOnlyRootFilesystem = true;

    private int runAsUser;

    private int runAsGroup;

    @Builder.Default
    private boolean runAsNonRoot = true;

    @Builder.Default
    private SeccompProfileType seccompProfileType = SeccompProfileType.RuntimeDefault;

    private boolean allowPrivilegeEscalation;

    @Builder.Default
    private CapabilitiesOptions capabilities = new CapabilitiesOptions();

    @Builder.Default
    private List<String> args = new ArrayList<>();
}

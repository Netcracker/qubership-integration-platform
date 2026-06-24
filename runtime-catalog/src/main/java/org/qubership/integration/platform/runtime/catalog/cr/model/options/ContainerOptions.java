package org.qubership.integration.platform.runtime.catalog.cr.model.options;

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
    private ImagePoolPolicy imagePoolPolicy = ImagePoolPolicy.IfNotPresent;

    private Limits request;

    private Limits limit;

    @Builder.Default
    private boolean readOnlyRootFilesystem = true;

    private int runAsUser;

    private int runAsGroup;

    @Builder.Default
    private boolean runAsNonRoot = true;

    @Builder.Default
    private SeccompProfileType seccompProfileType = SeccompProfileType.RuntimeDefault;

    private boolean allowPrivilegeEscalation;

    private CapabilitiesOptions capabilities;

    @Builder.Default
    private List<String> args = new ArrayList<>();
}

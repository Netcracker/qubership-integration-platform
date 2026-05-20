package org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContainerOptions {
    private String image;

    @Builder.Default
    private ImagePoolPolicy imagePoolPolicy = ImagePoolPolicy.IfNotPresent;
}

package org.qubership.integration.platform.camelk.model.options;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Collection;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CapabilitiesOptions {
    @Builder.Default
    private Collection<String> drop = new ArrayList<>();

    @Builder.Default
    private Collection<String> add = new ArrayList<>();
}

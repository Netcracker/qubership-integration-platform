package org.qubership.integration.platform.camelk.model.options;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashSet;
import java.util.Set;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class MountOptions {
    @Builder.Default
    private Set<String> emptyDirs = new HashSet<>();

    @Builder.Default
    private Set<String> resources = new HashSet<>();
}

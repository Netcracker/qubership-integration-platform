package org.qubership.integration.platform.runtime.catalog.cr.integrations.configuration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IntegrationsConfiguration {
    @Builder.Default
    List<SourceDefinition> sources = new ArrayList<>();

    @Builder.Default
    List<LibraryDefinition> libraries = new ArrayList<>();

    public IntegrationsConfiguration merge(IntegrationsConfiguration other) {
        return IntegrationsConfiguration.builder()
                // Merging sources by chain ID.
                .sources(mergeBy(SourceDefinition::getChainId, sources, other.sources))
                .libraries(mergeBy(LibraryDefinition::getSpecificationId, libraries, other.libraries))
                .build();
    }

    private static <T> List<T> mergeBy(Function<T, String> keyGetter, List<T> l1, List<T> l2) {
        return Stream.concat(l1.stream(), l2.stream())
                .sequential()
                .collect(Collectors.toMap(keyGetter, Function.identity(), (a, b) -> b))
                .values()
                .stream()
                .toList();
    }
}

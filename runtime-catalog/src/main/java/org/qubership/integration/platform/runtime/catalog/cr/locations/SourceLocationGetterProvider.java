package org.qubership.integration.platform.runtime.catalog.cr.locations;

import org.qubership.integration.platform.runtime.catalog.cr.ResourceBuildContext;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.function.Function;

@Component
public class SourceLocationGetterProvider {
    private final SourceMountPointGetter sourceMountPointGetter;

    @Autowired
    public SourceLocationGetterProvider(SourceMountPointGetter sourceMountPointGetter) {
        this.sourceMountPointGetter = sourceMountPointGetter;
    }

    public Function<ResourceBuildContext<Snapshot>, String> get(ResourceBuildContext<?> context) {
        // TODO add another source location support (f.e. from artifactory)
        return ctx -> "file:" + sourceMountPointGetter.apply(ctx);
    }
}

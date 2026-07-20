package org.qubership.integration.platform.camelk.locations;

import org.qubership.integration.platform.camelk.model.ResourceBuildContext;
import org.qubership.integration.platform.camelk.naming.NamingStrategy;
import org.qubership.integration.platform.chain.model.Snapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Paths;
import java.util.function.Function;

@Component
public class SourceMountPointGetter implements Function<ResourceBuildContext<Snapshot>, String> {
    private final NamingStrategy<ResourceBuildContext<Snapshot>> configMapNamingStrategy;
    private final String mountDir;

    @Autowired
    public SourceMountPointGetter(
            @Qualifier("sourceDslConfigMapNamingStrategy")
            NamingStrategy<ResourceBuildContext<Snapshot>> configMapNamingStrategy,

            @Value("${qip.cr.build.mount.path:/etc/camel/sources/}")
            String mountDir
    ) {
        this.configMapNamingStrategy = configMapNamingStrategy;
        this.mountDir = mountDir;
    }

    @Override
    public String apply(ResourceBuildContext<Snapshot> context) {
        String name = configMapNamingStrategy.getName(context);
        String fileName = String.format("%s.%s", name, context.getBuildInfo().getOptions().getLanguage());
        return Paths.get(mountDir, fileName).toString();
    }
}

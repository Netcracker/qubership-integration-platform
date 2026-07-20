package org.qubership.integration.platform.camelk.services;

import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.camelk.model.ResourceBuildContext;
import org.qubership.integration.platform.camelk.model.ResourceBuildError;
import org.qubership.integration.platform.camelk.model.ResourceBuilder;
import org.qubership.integration.platform.chain.model.Snapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class ResourceBuildService {
    private final List<ResourceBuilder<Snapshot>> chainResourceBuilders;
    private final List<ResourceBuilder<List<Snapshot>>> commonResourceBuilders;

    @Autowired
    public ResourceBuildService(
            List<ResourceBuilder<Snapshot>> chainResourceBuilders,
            List<ResourceBuilder<List<Snapshot>>> commonResourceBuilders
    ) {
        this.chainResourceBuilders = chainResourceBuilders;
        this.commonResourceBuilders = commonResourceBuilders;
    }

    public String buildResources(
            ResourceBuildContext<List<Snapshot>> buildContext
    ) {
        StringBuilder stringBuilder = new StringBuilder();
        try {
            for (Snapshot snapshot : buildContext.getData()) {
                applyBuilders(stringBuilder, buildContext.updateTo(snapshot), chainResourceBuilders);
            }
            applyBuilders(stringBuilder, buildContext, commonResourceBuilders);
            return stringBuilder.toString();
        } catch (Exception e) {
            log.error("Failed to build custom resource", e);
            throw new ResourceBuildError("Failed to build custom resource", e);
        }
    }

    private static <T> void applyBuilders(
            StringBuilder stringBuilder,
            ResourceBuildContext<T> context,
            List<ResourceBuilder<T>> builders
    ) throws Exception {
        for (var builder : builders) {
            if (builder.enabled(context)) {
                String built = builder.build(context);
                stringBuilder.append(built);
                ensureTrailingNewline(built, stringBuilder);
            }
        }
    }

    private static void ensureTrailingNewline(String built, StringBuilder stringBuilder) {
        if (!built.isEmpty() && built.charAt(built.length() - 1) != '\n') {
            stringBuilder.append('\n');
        }
    }


}

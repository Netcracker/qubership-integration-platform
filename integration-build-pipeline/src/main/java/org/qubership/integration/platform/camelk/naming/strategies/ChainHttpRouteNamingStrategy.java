package org.qubership.integration.platform.camelk.naming.strategies;

import org.qubership.integration.platform.camelk.model.ResourceBuildContext;
import org.qubership.integration.platform.camelk.naming.NamingStrategy;
import org.qubership.integration.platform.camelk.naming.validation.K8sNameValidator;
import org.qubership.integration.platform.camelk.naming.validation.K8sNameVerifier;
import org.qubership.integration.platform.camelk.naming.validation.K8sNames;
import org.qubership.integration.platform.chain.model.Snapshot;

import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * Names the HTTPRoute of one chain in one gateway tier of a micro-domain:
 * {@code <integration>-<chain suffix><tier suffix>}. The chain suffix is seeded the way
 * {@link SourceDslConfigMapNamingStrategy} seeds it, so the name stays the same across snapshots of the chain.
 */
public abstract class ChainHttpRouteNamingStrategy extends K8sResourceNamingStrategy<ResourceBuildContext<Snapshot>> {
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy;
    private final K8sNameValidator nameValidator;
    private final Function<Long, String> suffixGenerator;
    private final String tierSuffix;

    protected ChainHttpRouteNamingStrategy(
            K8sNameVerifier nameVerifier,
            K8sNameValidator nameValidator,
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy,
            Function<Long, String> suffixGenerator,
            String tierSuffix
    ) {
        super(nameVerifier);
        this.integrationResourceNamingStrategy = integrationResourceNamingStrategy;
        this.nameValidator = nameValidator;
        this.suffixGenerator = suffixGenerator;
        this.tierSuffix = tierSuffix;
    }

    @Override
    protected String proposeName(ResourceBuildContext<Snapshot> context) {
        String base = integrationResourceNamingStrategy.getName(context.updateTo(Collections.emptyList()));
        String chainId = context.getData().getChain().getId();
        String domainName = context.getBuildInfo().getOptions().getName();
        long seed = String.join("/", chainId, domainName).hashCode();
        String suffix = "-" + suffixGenerator.apply(seed) + tierSuffix;
        // Truncate the base, never the suffix: the suffix is what tells two chains and two tiers apart.
        int maxBaseLength = K8sNames.K8S_RESOURCE_NAME_LENGTH_LIMIT - suffix.length();
        if (maxBaseLength > 0 && base.length() > maxBaseLength) {
            base = base.substring(0, maxBaseLength);
        }
        return nameValidator.validate(base + suffix);
    }
}

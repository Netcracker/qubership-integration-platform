package org.qubership.integration.platform.engine.camel.dsl.preprocess.preprocessors;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.qubership.integration.platform.engine.camel.dsl.preprocess.ResourceContentPreprocessor;
import org.qubership.integration.platform.engine.util.InjectUtil;

import java.util.Optional;

@ApplicationScoped
@Priority(2)
public class MaasParametersResolverPreprocessor implements ResourceContentPreprocessor {
    private final Optional<MaasParametersResolver> maasParametersResolver;

    @Inject
    public MaasParametersResolverPreprocessor(Instance<MaasParametersResolver> maasParametersResolver) {
        this.maasParametersResolver = InjectUtil.injectOptional(maasParametersResolver);
    }

    @Override
    public String apply(String content) throws Exception {
        return maasParametersResolver.isPresent()
                ? maasParametersResolver.get().resolveMaasParameters(content)
                : content;
    }
}

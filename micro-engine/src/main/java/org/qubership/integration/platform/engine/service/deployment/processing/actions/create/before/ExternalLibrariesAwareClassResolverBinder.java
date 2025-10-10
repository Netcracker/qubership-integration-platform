package org.qubership.integration.platform.engine.service.deployment.processing.actions.create.before;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.apache.camel.CamelContext;
import org.apache.camel.spi.ClassResolver;
import org.qubership.integration.platform.engine.camel.QipCustomClassResolver;
import org.qubership.integration.platform.engine.camel.repository.RegistryHelper;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentUpdate;
import org.qubership.integration.platform.engine.model.deployment.update.ElementProperties;
import org.qubership.integration.platform.engine.service.deployment.processing.DeploymentProcessingAction;
import org.qubership.integration.platform.engine.service.deployment.processing.qualifiers.OnBeforeRoutesCreated;
import org.qubership.integration.platform.engine.service.externallibrary.ExternalLibraryService;
import org.qubership.integration.platform.engine.util.InjectUtil;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;

@ApplicationScoped
@OnBeforeRoutesCreated
public class ExternalLibrariesAwareClassResolverBinder implements DeploymentProcessingAction {
    private final Optional<ExternalLibraryService> externalLibraryService;
    private final CamelContext camelContext;

    @Inject
    public ExternalLibrariesAwareClassResolverBinder(
            Instance<ExternalLibraryService> externalLibraryService,
            CamelContext camelContext) {
        this.externalLibraryService = InjectUtil.injectOptional(externalLibraryService);
        this.camelContext = camelContext;
    }

    @Override
    public void execute(CamelContext context, DeploymentUpdate deploymentUpdate) {
        ClassResolver classResolver = buildClassResolver(context, deploymentUpdate);
        String deploymentId = deploymentUpdate.getDeploymentInfo().getDeploymentId();
        RegistryHelper.getRegistry(camelContext, deploymentId)
                .bind(deploymentId, ClassResolver.class, classResolver);
    }

    private ClassResolver buildClassResolver(CamelContext context, DeploymentUpdate deploymentUpdate) {
        Collection<String> systemModelIds = deploymentUpdate.getConfiguration().getProperties().stream()
                .map(ElementProperties::getProperties)
                .filter(properties -> CamelConstants.ChainProperties.SERVICE_CALL_ELEMENT
                        .equals(properties.get(CamelConstants.ChainProperties.ELEMENT_TYPE)))
                .map(properties -> properties.get(CamelConstants.ChainProperties.OPERATION_SPECIFICATION_ID))
                .filter(Objects::nonNull)
                .toList();
        ClassLoader classLoader = externalLibraryService.isPresent()
                ? externalLibraryService.get().getClassLoaderForSystemModels(systemModelIds, context.getApplicationContextClassLoader())
                : getClass().getClassLoader();
        return new QipCustomClassResolver(classLoader);
    }
}

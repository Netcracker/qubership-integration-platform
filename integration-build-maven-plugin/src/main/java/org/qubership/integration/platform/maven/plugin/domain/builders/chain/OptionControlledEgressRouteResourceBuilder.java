package org.qubership.integration.platform.maven.plugin.domain.builders.chain;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.qubership.integration.platform.camelk.builders.chain.EgressRouteResourceBuilder;
import org.qubership.integration.platform.camelk.model.ResourceBuildContext;
import org.qubership.integration.platform.camelk.naming.NamingStrategy;
import org.qubership.integration.platform.camelk.naming.validation.K8sNameValidator;
import org.qubership.integration.platform.camelk.services.RoutesGetterService;
import org.qubership.integration.platform.chain.model.Snapshot;
import org.qubership.integration.platform.maven.plugin.mojos.ControlPlaneType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class OptionControlledEgressRouteResourceBuilder extends EgressRouteResourceBuilder {
    @Autowired
    public OptionControlledEgressRouteResourceBuilder(
        @Qualifier("customResourceYamlMapper") YAMLMapper yamlMapper,
        RoutesGetterService routesGetterService,

        @Qualifier("httpRouteEgressNamingStrategy")
        NamingStrategy<ResourceBuildContext<List<Snapshot>>> httpRouteEgressNamingStrategy,

        K8sNameValidator k8sNameValidator
    ) {
        super(yamlMapper, routesGetterService, httpRouteEgressNamingStrategy, k8sNameValidator);
    }

    @Override
    public boolean enabled(ResourceBuildContext<List<Snapshot>> context) {
        return ControlPlaneUtil.enabled(context, ControlPlaneType.ISTIO) && super.enabled(context);
    }
}

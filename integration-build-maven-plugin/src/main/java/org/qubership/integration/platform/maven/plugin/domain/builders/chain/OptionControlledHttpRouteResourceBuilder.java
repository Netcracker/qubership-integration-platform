package org.qubership.integration.platform.maven.plugin.domain.builders.chain;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.qubership.integration.platform.camelk.builders.chain.HttpRouteResourceBuilder;
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
public class OptionControlledHttpRouteResourceBuilder extends HttpRouteResourceBuilder {
    @Autowired
    public OptionControlledHttpRouteResourceBuilder(
        @Qualifier("customResourceYamlMapper") YAMLMapper yamlMapper,
        RoutesGetterService routesGetterService,

        @Qualifier("httpRoutePublicNamingStrategy")
        NamingStrategy<ResourceBuildContext<List<Snapshot>>> httpRoutePublicNamingStrategy,

        @Qualifier("httpRoutePrivateNamingStrategy")
        NamingStrategy<ResourceBuildContext<List<Snapshot>>> httpRoutePrivateNamingStrategy,

        @Qualifier("serviceNamingStrategy")
        NamingStrategy<ResourceBuildContext<List<Snapshot>>> serviceNamingStrategy,

        K8sNameValidator k8sNameValidator
    ) {
        super(yamlMapper, routesGetterService, httpRoutePublicNamingStrategy, httpRoutePrivateNamingStrategy, serviceNamingStrategy, k8sNameValidator);
    }

    @Override
    public boolean enabled(ResourceBuildContext<List<Snapshot>> context) {
        return ControlPlaneUtil.enabled(context, ControlPlaneType.ISTIO) && super.enabled(context);
    }
}

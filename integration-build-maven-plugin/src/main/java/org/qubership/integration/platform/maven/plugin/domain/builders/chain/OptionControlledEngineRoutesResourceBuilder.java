package org.qubership.integration.platform.maven.plugin.domain.builders.chain;

import com.github.jknack.handlebars.Handlebars;
import org.qubership.integration.platform.camelk.builders.EngineRoutesResourceBuilder;
import org.qubership.integration.platform.camelk.model.ResourceBuildContext;
import org.qubership.integration.platform.camelk.naming.NamingStrategy;
import org.qubership.integration.platform.camelk.naming.validation.K8sNameValidator;
import org.qubership.integration.platform.chain.model.Snapshot;
import org.qubership.integration.platform.maven.plugin.mojos.ControlPlaneType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class OptionControlledEngineRoutesResourceBuilder extends EngineRoutesResourceBuilder {
    @Autowired
    public OptionControlledEngineRoutesResourceBuilder(
        Handlebars templates,

        @Qualifier("engineRoutesNamingStrategy")
        NamingStrategy<ResourceBuildContext<List<Snapshot>>> engineRoutesNamingStrategy,

        @Qualifier("serviceNamingStrategy")
        NamingStrategy<ResourceBuildContext<List<Snapshot>>> serviceNamingStrategy,

        K8sNameValidator k8sNameValidator
    ) {
        super(templates, engineRoutesNamingStrategy, serviceNamingStrategy, k8sNameValidator);
    }

    @Override
    public boolean enabled(ResourceBuildContext<List<Snapshot>> context) {
        return ControlPlaneUtil.enabled(context, ControlPlaneType.ISTIO) && super.enabled(context);
    }
}

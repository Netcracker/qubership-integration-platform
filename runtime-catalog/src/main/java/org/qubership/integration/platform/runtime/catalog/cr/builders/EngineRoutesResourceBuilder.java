package org.qubership.integration.platform.runtime.catalog.cr.builders;

import com.github.jknack.handlebars.Context;
import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Template;
import lombok.Builder;
import lombok.Data;
import org.qubership.integration.platform.runtime.catalog.cr.ResourceBuildContext;
import org.qubership.integration.platform.runtime.catalog.cr.ResourceBuilder;
import org.qubership.integration.platform.runtime.catalog.cr.naming.NamingStrategy;
import org.qubership.integration.platform.runtime.catalog.cr.naming.validation.K8sNameValidator;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class EngineRoutesResourceBuilder implements ResourceBuilder<List<Snapshot>> {
    private static final String TEMPLATE_NAME = "engine-routes";

    private static final String GATEWAY_API_GROUP = "gateway.networking.k8s.io";
    private static final String PUBLIC_GATEWAY_NAME = "public-gateway";
    private static final String PRIVATE_GATEWAY_NAME = "private-gateway";
    private static final String INTERNAL_GATEWAY_SERVICE_NAME = "internal-gateway-service";

    private static final String V1_ROUTE_PREFIX = "/v1/engine";
    private static final String SESSIONS_PATH = "/sessions";
    private static final String CHECKPOINT_SESSIONS_PATH_PREFIX = "/chains/";
    private static final String LIVE_EXCHANGES_PATH = "/live-exchanges";

    @Value("${qip.control-plane.routes.public.v1-prefix:/api/v1/qip/engine}")
    String publicRoutePrefixV1;

    @Value("${qip.cr.labels.domain}")
    String domainLabel;

    @Value("${qip.cr.labels.bg-version}")
    String bgVersionLabel;

    @Value("${spring.application.deployment_version}")
    String bgVersion;

    private final Handlebars templates;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> engineRoutesPublicNamingStrategy;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> engineRoutesPrivateNamingStrategy;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> engineRoutesInternalNamingStrategy;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> serviceNamingStrategy;
    private final K8sNameValidator k8sNameValidator;

    @Autowired
    public EngineRoutesResourceBuilder(
            Handlebars templates,

            @Qualifier("engineRoutesPublicNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> engineRoutesPublicNamingStrategy,

            @Qualifier("engineRoutesPrivateNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> engineRoutesPrivateNamingStrategy,

            @Qualifier("engineRoutesInternalNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> engineRoutesInternalNamingStrategy,

            @Qualifier("serviceNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> serviceNamingStrategy,

            K8sNameValidator k8sNameValidator
    ) {
        this.templates = templates;
        this.engineRoutesPublicNamingStrategy = engineRoutesPublicNamingStrategy;
        this.engineRoutesPrivateNamingStrategy = engineRoutesPrivateNamingStrategy;
        this.engineRoutesInternalNamingStrategy = engineRoutesInternalNamingStrategy;
        this.serviceNamingStrategy = serviceNamingStrategy;
        this.k8sNameValidator = k8sNameValidator;
    }

    @Data
    @Builder
    private static class ParentRefData {
        private String group;
        private String kind;
        private String name;
    }

    @Data
    @Builder
    private static class RuleData {
        private String path;
        private String rewritePath;
        private String backendServiceName;
    }

    @Data
    @Builder
    private static class TemplateData {
        private String name;
        private String domainLabel;
        private String domainName;
        private String bgVersionLabel;
        private String bgVersion;
        private List<ParentRefData> parentRefs;
        private List<RuleData> rules;
    }

    @Override
    public boolean enabled(ResourceBuildContext<List<Snapshot>> context) {
        return true;
    }

    @Override
    public String build(ResourceBuildContext<List<Snapshot>> context) throws Exception {
        String domain = context.getBuildInfo().getOptions().getName();
        String backendServiceName = serviceNamingStrategy.getName(context);

        RuleData sessionsRule = rule(domain, SESSIONS_PATH, backendServiceName);
        RuleData checkpointRule = rule(domain, CHECKPOINT_SESSIONS_PATH_PREFIX, backendServiceName);
        RuleData liveExchangesRule = rule(domain, LIVE_EXCHANGES_PATH, backendServiceName);

        StringBuilder out = new StringBuilder();
        out.append(renderTier(
                context,
                engineRoutesPublicNamingStrategy,
                List.of(
                        parentRef(GATEWAY_API_GROUP, "Gateway", PUBLIC_GATEWAY_NAME),
                        parentRef(GATEWAY_API_GROUP, "Gateway", PRIVATE_GATEWAY_NAME),
                        parentRef("", "Service", INTERNAL_GATEWAY_SERVICE_NAME)),
                List.of(sessionsRule, checkpointRule, liveExchangesRule)));
        out.append(renderTier(
                context,
                engineRoutesPrivateNamingStrategy,
                List.of(
                        parentRef(GATEWAY_API_GROUP, "Gateway", PRIVATE_GATEWAY_NAME),
                        parentRef("", "Service", INTERNAL_GATEWAY_SERVICE_NAME)),
                List.of(sessionsRule, checkpointRule)));
        out.append(renderTier(
                context,
                engineRoutesInternalNamingStrategy,
                List.of(parentRef("", "Service", INTERNAL_GATEWAY_SERVICE_NAME)),
                List.of(sessionsRule, checkpointRule)));
        return out.toString();
    }

    private String renderTier(
            ResourceBuildContext<List<Snapshot>> context,
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> namingStrategy,
            List<ParentRefData> parentRefs,
            List<RuleData> rules
    ) throws Exception {
        TemplateData templateData = TemplateData.builder()
                .name(namingStrategy.getName(context))
                .domainLabel(domainLabel)
                .domainName(k8sNameValidator.validate(context.getBuildInfo().getOptions().getName()))
                .bgVersionLabel(bgVersionLabel)
                .bgVersion(bgVersion)
                .parentRefs(parentRefs)
                .rules(rules)
                .build();
        Context templateContext = Context.newContext(templateData);
        Template template = templates.compile(TEMPLATE_NAME);
        return template.apply(templateContext);
    }

    private RuleData rule(String domain, String apiPath, String backendServiceName) {
        return RuleData.builder()
                .path(publicRoutePrefixV1 + "/" + domain + apiPath)
                .rewritePath(V1_ROUTE_PREFIX + apiPath)
                .backendServiceName(backendServiceName)
                .build();
    }

    private ParentRefData parentRef(String group, String kind, String name) {
        return ParentRefData.builder().group(group).kind(kind).name(name).build();
    }
}

package org.qubership.integration.platform.camelk.builders;

import com.github.jknack.handlebars.Context;
import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Template;
import lombok.Builder;
import lombok.Data;
import org.qubership.integration.platform.camelk.model.ResourceBuildContext;
import org.qubership.integration.platform.camelk.model.ResourceBuilder;
import org.qubership.integration.platform.camelk.naming.NamingStrategy;
import org.qubership.integration.platform.camelk.naming.validation.K8sNameValidator;
import org.qubership.integration.platform.chain.model.Snapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Generates the single HTTPRoute CR that exposes micro-engine's own REST endpoints
 * (sessions, checkpoint-sessions, live-exchanges) to the public, private, and internal
 * gateways.
 *
 * <p>The route table below is a hand-mirrored copy of
 * {@code org.qubership.integration.platform.engine.controlplane.RoutesRegistrator}
 * (micro-engine module). It is NOT derived from that class at build or run time — if
 * RoutesRegistrator's registered endpoints change, this table must be updated by hand.
 * {@code RoutesRegistrator.registerRoutes()} carries a reciprocal comment pointing back
 * here.
 *
 * <p>Every endpoint below is registered by RoutesRegistrator with at least PUBLIC type,
 * and a PUBLIC registration's RouteType semantics ("sent to internal, private and public
 * gateways") already require it to be reachable from all three gateways — so one CR with
 * all three parentRefs is sufficient. A per-tier CR split (like the sibling
 * HttpRouteResourceBuilder uses for per-chain trigger routes, whose tiers genuinely
 * differ) would only produce duplicate, conflicting HTTPRoute objects here.
 */
@Component
@ConditionalOnProperty(name = "qip.control-plane.mesh-type", havingValue = "Istio")
public class EngineRoutesResourceBuilder implements ResourceBuilder<List<Snapshot>> {
    private static final String TEMPLATE_NAME = "engine-routes";

    private static final String GATEWAY_API_GROUP = "gateway.networking.k8s.io";

    private static final String V1_ROUTE_PREFIX = "/v1/engine";
    private static final String SESSIONS_PATH = "/sessions";

    // CheckpointSessionController's real path is /chains/{chainId} -- a JAX-RS path
    // template. Gateway API's PathPrefix match has no {var} templating, so this is
    // truncated to the literal prefix before the variable; ReplacePrefixMatch preserves
    // the chain ID and everything after it when forwarding to the backend.
    private static final String CHECKPOINT_SESSIONS_PATH_PREFIX = "/chains/";

    // RoutesRegistrator registers this one with `to == from` (its 2-arg RouteEntry
    // constructor), which leaves the domain segment in the backend path.
    // LiveExchangesController's real @Path is the fixed /v1/engine/live-exchanges with no
    // domain segment, so this rewrites there instead of mirroring RoutesRegistrator's
    // literal (and actually unreachable) to==from.
    private static final String LIVE_EXCHANGES_PATH = "/live-exchanges";

    @Value("${qip.control-plane.routes.public.v1-prefix:/api/v1/qip/engine}")
    String publicRoutePrefixV1;

    @Value("${qip.gateway.public.name}")
    String publicGatewayName;

    @Value("${qip.gateway.private.name}")
    String privateGatewayName;

    @Value("${qip.gateway.internal.name}")
    String internalGatewayName;

    @Value("${qip.cr.labels.domain}")
    String domainLabel;

    @Value("${qip.cr.labels.bg-version}")
    String bgVersionLabel;

    @Value("${spring.application.deployment_version}")
    String bgVersion;

    private final Handlebars templates;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> engineRoutesNamingStrategy;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> serviceNamingStrategy;
    private final K8sNameValidator k8sNameValidator;

    @Autowired
    public EngineRoutesResourceBuilder(
            Handlebars templates,

            @Qualifier("engineRoutesNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> engineRoutesNamingStrategy,

            @Qualifier("serviceNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> serviceNamingStrategy,

            K8sNameValidator k8sNameValidator
    ) {
        this.templates = templates;
        this.engineRoutesNamingStrategy = engineRoutesNamingStrategy;
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
        // These endpoints always exist on every micro-engine instance; there is no
        // domain option that turns them off. (Note: this assumes the domain's backend
        // Service is enabled too, same latent assumption ServiceResourceBuilder's own
        // callers already rely on.)
        return true;
    }

    @Override
    public String build(ResourceBuildContext<List<Snapshot>> context) throws Exception {
        String domain = context.getBuildInfo().getOptions().getName();
        String backendServiceName = serviceNamingStrategy.getName(context);

        List<RuleData> rules = List.of(
                rule(domain, SESSIONS_PATH, backendServiceName),
                rule(domain, CHECKPOINT_SESSIONS_PATH_PREFIX, backendServiceName),
                rule(domain, LIVE_EXCHANGES_PATH, backendServiceName));
        List<ParentRefData> parentRefs = List.of(
                parentRef(GATEWAY_API_GROUP, "Gateway", publicGatewayName),
                parentRef(GATEWAY_API_GROUP, "Gateway", privateGatewayName),
                parentRef("", "Service", internalGatewayName));

        TemplateData templateData = TemplateData.builder()
                .name(engineRoutesNamingStrategy.getName(context))
                .domainLabel(domainLabel)
                .domainName(k8sNameValidator.validate(domain))
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

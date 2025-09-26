package org.qubership.integration.platform.engine.camel.metrics;

import io.micrometer.core.instrument.Tags;
import io.quarkus.micrometer.runtime.HttpServerMetricsTagsContributor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.http.common.CamelServlet;
import org.apache.camel.http.common.HttpCommonEndpoint;
import org.apache.camel.http.common.HttpConsumer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.qubership.integration.platform.engine.camel.components.servlet.CustomHttpRestServletResolveConsumerStrategy;
import org.qubership.integration.platform.engine.camel.components.servlet.ServletCustomEndpoint;
import org.qubership.integration.platform.engine.registry.GatewayHttpRegistry;

import static java.util.Objects.nonNull;

@ApplicationScoped
public class CamelServletMetricsTagsContributor implements HttpServerMetricsTagsContributor {
    private final GatewayHttpRegistry httpRegistry;
    private final String camelServletName;
    private final String camelRoutesPrefix;
    private final CustomHttpRestServletResolveConsumerStrategy resolveConsumerStrategy;

    @Inject
    public CamelServletMetricsTagsContributor(
            @ConfigProperty(name = "quarkus.camel.servlet.servlet-name") String servletName,
            @ConfigProperty(name = "qip.camel.routes-prefix") String routesPrefix,
            GatewayHttpRegistry httpRegistry,
            CustomHttpRestServletResolveConsumerStrategy resolveConsumerStrategy
    ) {
        this.camelServletName = servletName;
        this.camelRoutesPrefix = routesPrefix;
        this.httpRegistry = httpRegistry;
        this.resolveConsumerStrategy = resolveConsumerStrategy;
    }

    @Override
    public Tags contribute(Context context) {
        String path = context.request().path();

        if (!path.startsWith(camelRoutesPrefix)) {
            return Tags.empty();
        }

        String method = context.request().method().toString();

        CamelServlet camelServlet = (CamelServlet) httpRegistry.getCamelServlet(camelServletName);
        HttpConsumer consumer = resolveConsumerStrategy.resolvePath(
                path.substring(camelRoutesPrefix.length()),
                method,
                camelServlet.getConsumers()
        );

        Tags tags = Tags.empty();
        if (nonNull(consumer)) {
            HttpCommonEndpoint endpoint = consumer.getEndpoint();
            tags = tags.and("uri", camelRoutesPrefix + endpoint.getPath());
            if (endpoint instanceof ServletCustomEndpoint servletCustomEndpoint) {
                if (servletCustomEndpoint.getTagsProvider() != null) {
                    tags = tags.and(servletCustomEndpoint.getTagsProvider().get());
                }
            }

        }

        return tags;
    }
}

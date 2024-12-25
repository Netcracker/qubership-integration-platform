package org.qubership.integration.platform.engine.consul;

import org.springframework.boot.context.config.ConfigDataLocation;
import org.springframework.boot.context.config.ConfigDataLocationResolverContext;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.cloud.consul.config.ConsulConfigDataLocationResolver;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponents;

@Order(Ordered.HIGHEST_PRECEDENCE)
public class ConsulUrlWithSchemaDataLocationResolver extends ConsulConfigDataLocationResolver {
    protected ConsulUrlWithSchemaDataLocationResolver(DeferredLogFactory log) {
        super(log);
    }

    @Nullable
    @Override
    protected UriComponents parseLocation(ConfigDataLocationResolverContext context, ConfigDataLocation location) {
        String originalLocation = location.getNonPrefixedValue(PREFIX);
        if (!StringUtils.hasText(originalLocation)) {
            return null;
        }
        return super.parseLocation(context, ConfigDataLocation.of(PREFIX + originalLocation.replaceAll("^.+//", "")));
    }
}

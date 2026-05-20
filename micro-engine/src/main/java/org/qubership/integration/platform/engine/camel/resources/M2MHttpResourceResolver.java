package org.qubership.integration.platform.engine.camel.resources;

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.impl.engine.DefaultResourceResolvers;
import org.apache.camel.spi.Resource;
import org.apache.camel.spi.annotations.ResourceResolver;

@Slf4j
@ResourceResolver(DefaultResourceResolvers.HttpResolver.SCHEME)
public class M2MHttpResourceResolver extends DefaultResourceResolvers.HttpResolver {
    @Override
    public Resource createResource(String location, String remaining) {
        // TODO
        log.info("Resolving resource for location: {}", location);
        return super.createResource(location, remaining);
    }
}

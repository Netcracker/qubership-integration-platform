package org.qubership.integration.platform.engine.configuration.security;

import com.netcracker.cloud.security.core.utils.k8s.impl.UrlCache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class M2MFallbackConfiguration {

    @Named("m2mUrlCache")
    @Produces
    public UrlCache m2mUrlCache(
        @ConfigProperty(name = "qip.chains.http-client.m2m.fallback-interceptor.url-cache.size") int urlCacheSize,
        @ConfigProperty(name = "qip.chains.http-client.m2m.fallback-interceptor.url-cache.ttl-hours") int ttlHours
    ) {
        return new UrlCache(
            urlCacheSize,
            TimeUnit.HOURS.toSeconds(ttlHours)
        );
    }
}

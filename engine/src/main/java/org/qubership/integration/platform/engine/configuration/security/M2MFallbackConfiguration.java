package org.qubership.integration.platform.engine.configuration.security;

import com.netcracker.cloud.security.core.utils.k8s.impl.UrlCache;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.TimeUnit;

@AutoConfiguration
public class M2MFallbackConfiguration {

    private final int urlCacheSize;
    private final int ttlHours;

    public M2MFallbackConfiguration(
            @Value("${cip.chains.http-client.m2m.fallback-interceptor.url-cache.size:400}") int urlCacheSize,
            @Value("${cip.chains.http-client.m2m.fallback-interceptor.url-cache.ttl-hours:5}") int ttlHours) {
        this.urlCacheSize = urlCacheSize;
        this.ttlHours = ttlHours;
    }

    @Bean
    public UrlCache m2mUrlCache() {
        return new UrlCache(
                urlCacheSize,
                TimeUnit.HOURS.toSeconds(ttlHours)
        );
    }
}

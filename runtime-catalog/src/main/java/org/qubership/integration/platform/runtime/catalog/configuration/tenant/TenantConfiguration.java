package org.qubership.integration.platform.runtime.catalog.configuration.tenant;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Getter
@Configuration
public class TenantConfiguration {

    private final String defaultTenant;

    @Autowired
    public TenantConfiguration(@Value("${tenant.default.id:}") String defaultTenant) {
        if (StringUtils.isEmpty(defaultTenant)) {
            throw new BeanInitializationException(
                    "Default tenant is empty! Please specify application property [tenant.default.id]");
        }

        log.info("Default tenant id [tenant.default.id]: {}", defaultTenant);
        this.defaultTenant = defaultTenant;
    }
}

package org.qubership.integration.platform.runtime.catalog.service.variables;

import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.runtime.catalog.service.variables.secrets.SecretService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SecretNameResolverService {
    private final SecretService secretService;

    @Autowired
    public SecretNameResolverService(SecretService secretService) {
        this.secretService = secretService;
    }

    public String resolveSecretName(String secretName) {
        return StringUtils.isBlank(secretName) || "default".equalsIgnoreCase(secretName)
                ? secretService.getDefaultSecretName()
                : secretName;
    }
}

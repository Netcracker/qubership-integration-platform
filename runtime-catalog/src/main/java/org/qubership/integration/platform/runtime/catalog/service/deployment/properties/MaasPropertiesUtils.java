package org.qubership.integration.platform.runtime.catalog.service.deployment.properties;

import com.netcracker.cloud.dbaas.client.config.MSInfoProvider;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.qubership.integration.platform.runtime.catalog.configuration.tenant.TenantConfiguration;
import org.qubership.integration.platform.runtime.catalog.model.constant.CamelOptions;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class MaasPropertiesUtils {

    public static final String MAAS_CLASSIFIER_TENANT_ID_PROP = "maasClassifierTenantId";
    public static final String MAAS_CLASSIFIER_TENANT_ENABLED_PROP = "maasClassifierTenantEnabled";
    public static final String MAAS_CLASSIFIER_TENANT_ID_CAMEL_NAME = "maas.classifier.tenantId";
    public static final String MAAS_CLASSIFIER_TENANT_ENABLED_CAMEL_NAME = "maas.classifier.tenantEnabled";

    private final TenantConfiguration tenantConfiguration;
    private final MSInfoProvider msInfoProvider;

    @Autowired
    public MaasPropertiesUtils(TenantConfiguration tenantConfiguration, MSInfoProvider msInfoProvider) {
        this.tenantConfiguration = tenantConfiguration;
        this.msInfoProvider = msInfoProvider;
    }

    public void enrichWithMaasEnvProperties(ChainElement element, @NotNull Map<String, String> elementProperties) {
        String maasClassifierNamespace = element.getPropertyAsString(CamelOptions.MAAS_CLASSIFIER_NAMESPACE_PROP);
        String maasTenantTopicEnabled = element.getPropertyAsString(MAAS_CLASSIFIER_TENANT_ENABLED_PROP);
        String maasTenantId = element.getPropertyAsString(MAAS_CLASSIFIER_TENANT_ID_PROP);

        elementProperties.put(
                CamelOptions.MAAS_CLASSIFIER_NAMESPACE_PROP,
                StringUtils.isNotEmpty(maasClassifierNamespace) ? maasClassifierNamespace : msInfoProvider.getNamespace()
        );
        elementProperties.put(
                MAAS_CLASSIFIER_TENANT_ENABLED_PROP,
                StringUtils.isNotEmpty(maasTenantTopicEnabled) ? maasTenantTopicEnabled : Boolean.FALSE.toString()
        );
        elementProperties.put(
                MAAS_CLASSIFIER_TENANT_ID_PROP,
                StringUtils.isNotEmpty(maasTenantId) ? maasTenantId : tenantConfiguration.getDefaultTenant()
        );
    }
}

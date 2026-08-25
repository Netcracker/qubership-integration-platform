package org.qubership.integration.platform.runtime.catalog.service.deployment.properties;

import com.netcracker.cloud.dbaas.client.config.MSInfoProvider;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.qubership.integration.platform.chain.model.Element;
import org.qubership.integration.platform.library.constants.CamelOptions;
import org.qubership.integration.platform.runtime.catalog.configuration.tenant.TenantConfiguration;
import org.qubership.integration.platform.util.ElementUtils;
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

    /** Reads a MaaS classifier key from the element's active environment. */
    public static String envScopeValue(Element element, String key, String defaultValue) {
        return scopeValue(element.getEnvironment().map(environment -> (Map<String, Object>) environment.getProperties()).orElse(null), key, defaultValue);
    }

    /**
     * Reads a MaaS classifier key from whichever property scope the caller resolved: the environment's
     * properties for async elements, the element's own properties for standalone Kafka and AMQP elements.
     */
    public static String scopeValue(Map<String, Object> props, String key, String defaultValue) {
        Object value = props == null ? null : props.get(key);
        return value == null ? defaultValue : value.toString();
    }

    public void enrichWithMaasEnvProperties(Element element, @NotNull Map<String, String> elementProperties) {
        String maasClassifierNamespace = ElementUtils.getPropertyAsString(element, CamelOptions.MAAS_CLASSIFIER_NAMESPACE_PROP);
        String maasTenantTopicEnabled = ElementUtils.getPropertyAsString(element, MAAS_CLASSIFIER_TENANT_ENABLED_PROP);
        String maasTenantId = ElementUtils.getPropertyAsString(element, MAAS_CLASSIFIER_TENANT_ID_PROP);

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

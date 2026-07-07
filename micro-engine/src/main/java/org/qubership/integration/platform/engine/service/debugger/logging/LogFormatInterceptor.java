package org.qubership.integration.platform.engine.service.debugger.logging;

import io.smallrye.config.ConfigSourceInterceptor;
import io.smallrye.config.ConfigSourceInterceptorContext;
import io.smallrye.config.ConfigValue;

public class LogFormatInterceptor implements ConfigSourceInterceptor {
    @Override
    public ConfigValue getValue(ConfigSourceInterceptorContext context, String name) {
        if ("quarkus.log.json.console.enabled".equals(name)) {
            ConfigValue formatConfig = context.proceed("qip.logging.format");

            boolean shouldEnableJson = (formatConfig == null || "json".equalsIgnoreCase(formatConfig.getValue()));

            return ConfigValue.builder()
                    .withName(name)
                    .withValue(shouldEnableJson ? "true" : "false")
                    .build();
        }
        return context.proceed(name);
    }
}

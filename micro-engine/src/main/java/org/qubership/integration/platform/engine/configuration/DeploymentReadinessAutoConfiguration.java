package org.qubership.integration.platform.engine.configuration;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.qubership.integration.platform.engine.events.CommonVariablesUpdatedEvent;
import org.qubership.integration.platform.engine.events.SecuredVariablesUpdatedEvent;
import org.qubership.integration.platform.engine.events.UpdateEvent;
import org.qubership.integration.platform.engine.util.DevModeUtil;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class DeploymentReadinessAutoConfiguration {
    public static final String DEPLOYMENT_READINESS_EVENTS_BEAN = "deploymentReadinessEvents";

    @Bean(DEPLOYMENT_READINESS_EVENTS_BEAN)
    @ConditionalOnMissingBean(name = DEPLOYMENT_READINESS_EVENTS_BEAN)
    Set<Class<? extends UpdateEvent>> deploymentReadinessEvents(DevModeUtil devModeUtil) {
        Set<Class<? extends UpdateEvent>> events = new HashSet<>();
        events.add(CommonVariablesUpdatedEvent.class);
        if (!devModeUtil.isDevMode()) {
            events.add(SecuredVariablesUpdatedEvent.class);
        }
        return Collections.unmodifiableSet(events);
    }
}

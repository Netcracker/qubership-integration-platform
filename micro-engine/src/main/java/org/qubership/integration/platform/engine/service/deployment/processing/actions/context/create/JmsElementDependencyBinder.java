/*
 * Copyright 2024-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.qubership.integration.platform.engine.service.deployment.processing.actions.context.create;

import javax.naming.Context;
import javax.naming.NamingException;
import jakarta.jms.ConnectionFactory;
import org.springframework.jms.support.destination.JndiDestinationResolver;
import org.springframework.jndi.JndiObjectFactoryBean;
import org.springframework.jndi.JndiTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.apache.camel.spring.SpringCamelContext;
import org.apache.camel.component.jms.JmsComponent;
import org.apache.camel.component.jms.JmsConfiguration;
import org.apache.camel.spi.ThreadPoolProfile;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.model.deployment.update.ElementProperties;
import org.qubership.integration.platform.engine.service.deployment.processing.ElementProcessingAction;
import org.qubership.integration.platform.engine.service.deployment.processing.qualifiers.OnAfterDeploymentContextCreated;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.ChainProperties;
import org.qubership.integration.platform.engine.service.VariablesService;
import org.qubership.integration.platform.engine.model.ChainElementType;
import org.qubership.integration.platform.engine.jms.weblogic.WeblogicSecureThreadFactory;
import org.qubership.integration.platform.engine.jms.weblogic.WeblogicSecurityBean;
import org.qubership.integration.platform.engine.jms.weblogic.WeblogicSecurityInterceptStrategy;
import java.util.Map;
import java.util.Properties;
import java.lang.RuntimeException;

@Component
@OnAfterDeploymentContextCreated
public class JmsElementDependencyBinder extends ElementProcessingAction {
    private final VariablesService variablesService;
    private final ObjectProvider<WeblogicSecurityBean> wlSecurityBeanProvider;
    private final ObjectProvider<WeblogicSecurityInterceptStrategy> wlSecurityInterceptStrategyProvider;
    private final ObjectProvider<WeblogicSecureThreadFactory> wlSecureThreadFactoryProvider;
    
    @Autowired
    public JmsElementDependencyBinder(
        VariablesService variablesService,
        ObjectProvider<WeblogicSecurityBean> wlSecurityBeanProvider,
        ObjectProvider<WeblogicSecurityInterceptStrategy> wlSecurityInterceptStrategyProvider,
        ObjectProvider<WeblogicSecureThreadFactory> wlSecureThreadFactoryProvider
    ) {
        this.variablesService = variablesService;
        this.wlSecurityBeanProvider = wlSecurityBeanProvider;
        this.wlSecurityInterceptStrategyProvider = wlSecurityInterceptStrategyProvider;
        this.wlSecureThreadFactoryProvider = wlSecureThreadFactoryProvider;
    }

    @Override
    public boolean applicableTo(ElementProperties properties) {
        ChainElementType elementType = ChainElementType.fromString(
                properties.getProperties().get(ChainProperties.ELEMENT_TYPE));
        return ChainElementType.JMS_SENDER.equals(elementType)
                || ChainElementType.JMS_TRIGGER.equals(elementType);
    }

    @Override
    public void apply(
        SpringCamelContext context,
        ElementProperties elementProperties,
        DeploymentInfo deploymentInfo
    ) {
        String elementId = elementProperties.getElementId();
        Map<String, String> properties = elementProperties.getProperties();
        Properties environment = new Properties();
        String jmsInitialContextFactory = variablesService.injectVariables(
            properties.get(ChainProperties.JMS_INITIAL_CONTEXT_FACTORY));
        String jmsProviderUrl = variablesService.injectVariables(properties.get(
            ChainProperties.JMS_PROVIDER_URL));
        String jmsConnectionFactoryName = variablesService.injectVariables(
            properties.get(ChainProperties.JMS_CONNECTION_FACTORY_NAME));

        String username = variablesService.injectVariables(properties.get(
            ChainProperties.JMS_USERNAME));
        String password = variablesService.injectVariables(properties.get(
            ChainProperties.JMS_PASSWORD));

        environment.put(Context.INITIAL_CONTEXT_FACTORY, jmsInitialContextFactory);
        environment.put(Context.PROVIDER_URL, jmsProviderUrl);

        boolean secured = !StringUtils.isBlank(username) && !StringUtils.isBlank(password);
        if (secured) {
            environment.put(Context.SECURITY_PRINCIPAL, username);
            environment.put(Context.SECURITY_CREDENTIALS, password);
        }

        JndiTemplate jmsJndiTemplate = new JndiTemplate(environment);

        JndiObjectFactoryBean jmsConnectionFactory = new JndiObjectFactoryBean();
        jmsConnectionFactory.setJndiTemplate(jmsJndiTemplate);
        jmsConnectionFactory.setJndiName(jmsConnectionFactoryName);
        jmsConnectionFactory.setProxyInterface(ConnectionFactory.class);
        jmsConnectionFactory.setLookupOnStartup(false);
        jmsConnectionFactory.setExposeAccessContext(true);
        try {
            jmsConnectionFactory.afterPropertiesSet();
        } catch (NamingException exception) {
            throw new RuntimeException("Failed to create JMS connection factory", exception);
        }

        JndiDestinationResolver jndiDestinationResolver = new JndiDestinationResolver();
        jndiDestinationResolver.setJndiTemplate(jmsJndiTemplate);
        jndiDestinationResolver.setFallbackToDynamicDestination(true);

        JmsConfiguration jmsConfiguration = new JmsConfiguration();
        jmsConfiguration.setConnectionFactory((ConnectionFactory) jmsConnectionFactory.getObject());
        jmsConfiguration.setDestinationResolver(jndiDestinationResolver);

        WeblogicSecurityBean wlSecurityBean = wlSecurityBeanProvider.getIfAvailable();
        WeblogicSecureThreadFactory wlSecureThreadFactory = wlSecureThreadFactoryProvider.getIfAvailable();
        WeblogicSecurityInterceptStrategy wlSecurityInterceptStrategy = wlSecurityInterceptStrategyProvider.getIfAvailable();
        if (secured && wlSecurityBean != null && wlSecureThreadFactory != null &&
            wlSecurityInterceptStrategy != null
        ) {
            wlSecurityBean.setProviderUrl(jmsProviderUrl);
            wlSecurityBean.setSecurityPrincipal(username);
            wlSecurityBean.setSecurityCredentials(password);

            wlSecureThreadFactory.setName("jms-thread-factory-" + elementId);
            wlSecureThreadFactory.setWeblogicSecurityBean(wlSecurityBean);

            ThreadPoolProfile profile = context.getExecutorServiceManager().getDefaultThreadPoolProfile();
            ThreadPoolTaskExecutor jmsTaskExecutor = new ThreadPoolTaskExecutor();
            jmsTaskExecutor.setBeanName("jms-task-executor-" + elementId);
            jmsTaskExecutor.setThreadFactory(wlSecureThreadFactory);
            jmsTaskExecutor.setCorePoolSize(profile.getPoolSize());
            jmsTaskExecutor.setMaxPoolSize(profile.getMaxPoolSize());
            jmsTaskExecutor.setKeepAliveSeconds(profile.getKeepAliveTime().intValue());
            jmsTaskExecutor.setQueueCapacity(profile.getMaxQueueSize());
            jmsTaskExecutor.afterPropertiesSet();

            jmsConfiguration.setTaskExecutor(jmsTaskExecutor);

            wlSecurityInterceptStrategy.setTargetId(elementId);
            wlSecurityInterceptStrategy.setWeblogicSecurityBean(wlSecurityBean);

            context.getCamelContextExtension().addInterceptStrategy(wlSecurityInterceptStrategy);
        }

        JmsComponent jmsComponent = new JmsComponent(jmsConfiguration);

        String componentName = buildJmsComponentName(elementId, properties);
        context.addComponent(componentName, jmsComponent);
    }

    private String buildJmsComponentName(String elementId, Map<String, String> properties) {
        return String.format("jms-%s", elementId);
    }
}

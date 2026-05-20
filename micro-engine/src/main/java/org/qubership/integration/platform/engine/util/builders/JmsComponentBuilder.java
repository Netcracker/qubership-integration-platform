package org.qubership.integration.platform.engine.util.builders;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.jms.ConnectionFactory;
import org.apache.camel.CamelContext;
import org.apache.camel.component.jms.JmsComponent;
import org.apache.camel.component.jms.JmsConfiguration;
import org.apache.camel.spi.ThreadPoolProfile;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.engine.jms.weblogic.JndiDestinationResolver;
import org.qubership.integration.platform.engine.jms.weblogic.WeblogicSecureThreadFactory;
import org.qubership.integration.platform.engine.jms.weblogic.WeblogicSecurityBean;
import org.qubership.integration.platform.engine.jms.weblogic.WeblogicSecurityInterceptStrategy;
import org.springframework.jndi.JndiTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Properties;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;

public class JmsComponentBuilder {
    private String elementId;
    private String initialContextFactory;
    private String providerUrl;
    private String connectionFactoryName;
    private String username;
    private String password;

    public JmsComponentBuilder elementId(String elementId) {
        this.elementId = elementId;
        return this;
    }

    public JmsComponentBuilder initialContextFactory(String initialContextFactory) {
        this.initialContextFactory = initialContextFactory;
        return this;
    }

    public JmsComponentBuilder providerUrl(String providerUrl) {
        this.providerUrl = providerUrl;
        return this;
    }

    public JmsComponentBuilder connectionFactoryName(String connectionFactoryName) {
        this.connectionFactoryName = connectionFactoryName;
        return this;
    }

    public JmsComponentBuilder username(String username) {
        this.username = username;
        return this;
    }

    public JmsComponentBuilder password(String password) {
        this.password = password;
        return this;
    }

    public JmsComponent build() {
        Properties environment = new Properties();
        environment.put(Context.INITIAL_CONTEXT_FACTORY, initialContextFactory);
        environment.put(Context.PROVIDER_URL, providerUrl);

        boolean secured = !StringUtils.isBlank(username) && !StringUtils.isBlank(password);
        if (secured) {
            environment.put(Context.SECURITY_PRINCIPAL, username);
            environment.put(Context.SECURITY_CREDENTIALS, password);
        }

        Context initialContext;
        ConnectionFactory connectionFactory;
        try {
            initialContext = new InitialContext(environment);
            connectionFactory = (ConnectionFactory) initialContext.lookup(connectionFactoryName);
        } catch (NamingException e) {
            throw new RuntimeException("Unable to create JMS connection", e);
        }

        JndiTemplate jmsJndiTemplate = new JndiTemplate(environment);
        JndiDestinationResolver jndiDestinationResolver = new JndiDestinationResolver();
        jndiDestinationResolver.setJndiTemplate(jmsJndiTemplate);
        jndiDestinationResolver.setFallbackToDynamicDestination(true);

        JmsConfiguration jmsConfiguration = new JmsConfiguration();
        jmsConfiguration.setConnectionFactory(connectionFactory);
        jmsConfiguration.setDestinationResolver(jndiDestinationResolver);

        Instance<WeblogicSecurityBean> wlSecurityBeanProvider =
                CDI.current().select(WeblogicSecurityBean.class);
        Instance<WeblogicSecurityInterceptStrategy> wlSecurityInterceptStrategyProvider =
                CDI.current().select(WeblogicSecurityInterceptStrategy.class);
        Instance<WeblogicSecureThreadFactory> wlSecureThreadFactoryProvider =
                CDI.current().select(WeblogicSecureThreadFactory.class);
        CamelContext camelContext = CDI.current().select(CamelContext.class).get();

        WeblogicSecurityBean wlSecurityBean = wlSecurityBeanProvider.isUnsatisfied() ? null : wlSecurityBeanProvider.get();
        WeblogicSecureThreadFactory wlSecureThreadFactory = wlSecureThreadFactoryProvider.isUnsatisfied() ? null : wlSecureThreadFactoryProvider.get();
        WeblogicSecurityInterceptStrategy wlSecurityInterceptStrategy = wlSecurityInterceptStrategyProvider.isUnsatisfied() ? null : wlSecurityInterceptStrategyProvider.get();
        if (secured && wlSecurityBean != null && wlSecureThreadFactory != null
                && wlSecurityInterceptStrategy != null
        ) {
            wlSecurityBean.setProviderUrl(providerUrl);
            wlSecurityBean.setSecurityPrincipal(username);
            wlSecurityBean.setSecurityCredentials(password);

            wlSecureThreadFactory.setName("jms-thread-factory-" + elementId);
            wlSecureThreadFactory.setWeblogicSecurityBean(wlSecurityBean);

            ThreadPoolProfile profile = camelContext.getExecutorServiceManager().getDefaultThreadPoolProfile();
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

            camelContext.getCamelContextExtension().addInterceptStrategy(wlSecurityInterceptStrategy);
        }

        return new JmsComponent(jmsConfiguration);
    }
}

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

package org.qubership.integration.platform.engine.camel.components.rabbitmq;

import com.rabbitmq.client.MetricsCollector;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Category;
import org.apache.camel.Component;
import org.apache.camel.component.springrabbit.SpringRabbitMQConstants;
import org.apache.camel.component.springrabbit.SpringRabbitMQEndpoint;
import org.apache.camel.spi.UriEndpoint;
import org.apache.camel.spi.UriParam;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.amqp.RabbitConnectionDetails.Address;
import org.springframework.util.Assert;

@Slf4j
@Setter
@UriEndpoint(
    firstVersion = "3.8.0",
    scheme = "rabbitmq-custom",
    title = "Spring RabbitMQ custom",
    syntax = "rabbitmq-custom:exchangeName",
    category = {Category.MESSAGING},
    headersClass = SpringRabbitMQConstants.class
)
public class SpringRabbitMQCustomEndpoint extends SpringRabbitMQEndpoint {

    // connection params for connection factory builder that removed in spring-rabbit
    @UriParam(label = "common")
    private String addresses;
    @UriParam(label = "security", defaultValue = com.rabbitmq.client.ConnectionFactory.DEFAULT_USER, secret = true)
    private String username = com.rabbitmq.client.ConnectionFactory.DEFAULT_USER;
    @UriParam(label = "security", defaultValue = com.rabbitmq.client.ConnectionFactory.DEFAULT_PASS, secret = true)
    private String password = com.rabbitmq.client.ConnectionFactory.DEFAULT_PASS;
    @UriParam(label = "common", defaultValue = com.rabbitmq.client.ConnectionFactory.DEFAULT_VHOST)
    private String vhost = com.rabbitmq.client.ConnectionFactory.DEFAULT_VHOST;
    @UriParam(label = "advanced", defaultValue = ""
        + com.rabbitmq.client.ConnectionFactory.DEFAULT_CONNECTION_TIMEOUT)
    private int connectionTimeout = com.rabbitmq.client.ConnectionFactory.DEFAULT_CONNECTION_TIMEOUT;
    @UriParam(label = "advanced", defaultValue = ""
        + com.rabbitmq.client.ConnectionFactory.DEFAULT_CHANNEL_MAX)
    private int requestedChannelMax = com.rabbitmq.client.ConnectionFactory.DEFAULT_CHANNEL_MAX;
    @UriParam(label = "advanced", defaultValue = ""
        + com.rabbitmq.client.ConnectionFactory.DEFAULT_FRAME_MAX)
    private int requestedFrameMax = com.rabbitmq.client.ConnectionFactory.DEFAULT_FRAME_MAX;
    @UriParam(label = "advanced", defaultValue = ""
        + com.rabbitmq.client.ConnectionFactory.DEFAULT_HEARTBEAT)
    private int requestedHeartbeat = com.rabbitmq.client.ConnectionFactory.DEFAULT_HEARTBEAT;
    @UriParam(label = "security")
    private String sslProtocol;
    @UriParam(label = "security")
    private TrustManager trustManager;
    @UriParam(label = "advanced")
    private Map<String, Object> clientProperties;

    @UriParam(label = "common", description = "Metrics collector")
    private MetricsCollector metricsCollector;

    public SpringRabbitMQCustomEndpoint(String endpointUri, Component component,
        String exchangeName) {
        super(endpointUri, component, exchangeName);
    }


    @Override
    public void configureProperties(Map<String, Object> options) {
        super.configureProperties(options);
        setConnectionFactory(buildConnectionFactory());
    }

    private ConnectionFactory buildConnectionFactory() {
        com.rabbitmq.client.ConnectionFactory factory = new com.rabbitmq.client.ConnectionFactory();
        // CachingConnectionFactory has its own recovery mechanism
        factory.setAutomaticRecoveryEnabled(false);
        return new CachingConnectionFactory(setupFactory(factory));
    }

    /**
     * Setup logic copied from old camel-rabbitmq (v3.14) component
     */
    private com.rabbitmq.client.ConnectionFactory setupFactory(
        com.rabbitmq.client.ConnectionFactory factory) {
        factory.setMetricsCollector(metricsCollector);

        List<Address> addresses = new ArrayList<>();
        for (String address : getAddresses().split(",")) {
            int portSeparatorIndex = address.lastIndexOf(':');
            String host = address.substring(0, portSeparatorIndex);
            String port = address.substring(portSeparatorIndex + 1);
            addresses.add(new Address(host, Integer.parseInt(port)));
        }

        Assert.state(!addresses.isEmpty(), "Address list is empty");
        factory.setHost(addresses.get(0).host());
        factory.setPort(addresses.get(0).port());
        factory.setUsername(getUsername());
        factory.setPassword(getPassword());
        factory.setVirtualHost(getVhost());

        if (getClientProperties() != null) {
            factory.setClientProperties(getClientProperties());
        }

        factory.setConnectionTimeout(getConnectionTimeout());
        factory.setRequestedChannelMax(getRequestedChannelMax());
        factory.setRequestedFrameMax(getRequestedFrameMax());
        factory.setRequestedHeartbeat(getRequestedHeartbeat());

        setupSSL(factory);

        return factory;
    }

    /**
     * Due to {@link com.rabbitmq.client.ConnectionFactory#useSslProtocol()} javadoc,
     * this approach is insecure and need to be rewritten with
     * {@link com.rabbitmq.client.ConnectionFactory#useSslProtocol(SSLContext)}!
     */
    private void setupSSL(com.rabbitmq.client.ConnectionFactory factory) {
        if (getSslProtocol() != null) {
            try {
                if (getSslProtocol().equals("true")) {
                    factory.useSslProtocol();
                } else if (getTrustManager() == null) {
                    factory.useSslProtocol(getSslProtocol());
                } else {
                    // newer used in our element
                    factory.useSslProtocol(getSslProtocol(), getTrustManager());
                }
            } catch (NoSuchAlgorithmException | KeyManagementException e) {
                throw new IllegalArgumentException("Invalid sslProtocol " + getSslProtocol(), e);
            }
        }
    }

    @Override
    public String getQueues() {
        return super.getQueues();
    }

    /**
     * If this option is set, camel-rabbitmq will try to create connection based on the setting of
     * option addresses. The addresses value is a string which looks like "server1:12345,
     * server2:12345"
     */
    public void setAddresses(String addresses) {
        this.addresses = addresses;
    }

    public String getAddresses() {
        return addresses;
    }

    public String getUsername() {
        return username;
    }

    /**
     * Username in case of authenticated access
     */
    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    /**
     * Password for authenticated access
     */
    public void setPassword(String password) {
        this.password = password;
    }

    public String getVhost() {
        return vhost;
    }

    /**
     * The vhost for the channel
     */
    public void setVhost(String vhost) {
        this.vhost = vhost;
    }


    public int getConnectionTimeout() {
        return connectionTimeout;
    }

    /**
     * Connection timeout
     */
    public void setConnectionTimeout(int connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }

    public int getRequestedChannelMax() {
        return requestedChannelMax;
    }

    /**
     * Connection requested channel max (max number of channels offered)
     */
    public void setRequestedChannelMax(int requestedChannelMax) {
        this.requestedChannelMax = requestedChannelMax;
    }

    public int getRequestedFrameMax() {
        return requestedFrameMax;
    }

    /**
     * Connection requested frame max (max size of frame offered)
     */
    public void setRequestedFrameMax(int requestedFrameMax) {
        this.requestedFrameMax = requestedFrameMax;
    }

    public int getRequestedHeartbeat() {
        return requestedHeartbeat;
    }

    /**
     * Connection requested heartbeat (heart-beat in seconds offered)
     */
    public void setRequestedHeartbeat(int requestedHeartbeat) {
        this.requestedHeartbeat = requestedHeartbeat;
    }


    public String getSslProtocol() {
        return sslProtocol;
    }

    /**
     * Enables SSL on connection, accepted value are `true`, `TLS` and 'SSLv3`
     */
    public void setSslProtocol(String sslProtocol) {
        this.sslProtocol = sslProtocol;
    }

    public TrustManager getTrustManager() {
        return trustManager;
    }

    /**
     * Configure SSL trust manager, SSL should be enabled for this option to be effective
     */
    public void setTrustManager(TrustManager trustManager) {
        this.trustManager = trustManager;
    }

    public Map<String, Object> getClientProperties() {
        return clientProperties;
    }

    /**
     * Connection client properties (client info used in negotiating with the server)
     */
    public void setClientProperties(Map<String, Object> clientProperties) {
        this.clientProperties = clientProperties;
    }

    /**
     * MetricsCollector
     */
    public MetricsCollector getMetricsCollector() {
        return metricsCollector;
    }

    /**
     * MetricsCollector
     */
    public void setMetricsCollector(MetricsCollector metricsCollector) {
        this.metricsCollector = metricsCollector;
    }
}

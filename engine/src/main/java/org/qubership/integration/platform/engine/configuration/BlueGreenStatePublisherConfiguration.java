package org.qubership.integration.platform.engine.configuration;

import com.netcracker.cloud.bluegreen.api.service.BlueGreenStatePublisher;
import com.netcracker.cloud.bluegreen.impl.service.ConsulBlueGreenStatePublisher;
import org.qubership.integration.platform.engine.consul.ConsulClient;
import org.qubership.integration.platform.engine.secrets.PodSecrets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
public class BlueGreenStatePublisherConfiguration {
    private final String consulToken;
    private final String consulURL;
    private final String namespace;

    public BlueGreenStatePublisherConfiguration(
        @Value("${consul.token}") String consulToken,
        @Value("${consul.url}") String consulURL,
        @Value("${cloud.microservice.namespace}") String namespace
    ) {
        this.consulToken = consulToken;
        this.consulURL = consulURL.replaceFirst("\\/$", "");
        this.namespace = namespace;
    }

    //TODO: bound with application property
    @Bean
    @Profile("!development")
    public BlueGreenStatePublisher deploymentVersionTracker() {
        return new ConsulBlueGreenStatePublisher(this::getConsulToken, consulURL, namespace);
    }

    private String getConsulToken() {
        String token = PodSecrets.read(ConsulClient.CONSUL_TOKEN_SECRET_PROPERTY);
        return token == null ? consulToken : token;
    }
}

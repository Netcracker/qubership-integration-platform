package org.qubership.integration.platform.engine.configuration;

import com.netcracker.cloud.context.propagation.core.RequestContextPropagation;
import com.netcracker.cloud.maas.client.api.MaaSAPIClient;
import com.netcracker.cloud.maas.client.api.kafka.KafkaMaaSClient;
import com.netcracker.cloud.maas.client.api.rabbit.RabbitMaaSClient;
import com.netcracker.cloud.maas.client.impl.MaaSAPIClientImpl;
import com.netcracker.cloud.maas.client.impl.http.HttpClient;
import com.netcracker.cloud.security.core.utils.tls.TlsUtils;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.qubership.integration.platform.engine.cloudcore.maas.localdev.LocalDevMaaSAPIClient;
import org.qubership.integration.platform.engine.configuration.security.TokenSupplierProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;

import java.util.function.Supplier;

@Configuration
@Slf4j
public class MaasClientConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "tokenSupplierProvider")
    TokenSupplierProvider tokenSupplierProvider() {
        return () -> () -> "";
    }

    @Bean
    @ConditionalOnProperty(name = "maas.localdev", havingValue = "false", matchIfMissing = true)
    MaaSAPIClient maasClient(TokenSupplierProvider tokenSupplierProvider) {
        return new MaaSAPIClientImpl(tokenSupplierProvider.tokenSupplier());
    }

    @Bean
    @ConditionalOnProperty(name = "maas.localdev", havingValue = "true")
    MaaSAPIClient localDevMaasClient(
            @Value("${maas.agent.url}") String agentUrl,
            HttpClient maasRestClient
    ) {
        return new LocalDevMaaSAPIClient(agentUrl, maasRestClient);
    }

    @Bean
    @ConditionalOnProperty(name = "maas.localdev", havingValue = "true")
    HttpClient maasRestClient(
            @Value("${cloud.microservice.namespace}") String namespace,
            @Value("${maas.agent.username}") String username,
            @Value("${maas.agent.password}") String password
    ) throws Exception {
        String token = HttpHeaders.encodeBasicAuth(username, password, null);
        Supplier<String> tokenSupplier = () -> token;
        HttpClient httpClient = new HttpClient(tokenSupplier);
        try {
            OkHttpClient client = new OkHttpClient.Builder()
                    .sslSocketFactory(TlsUtils.getSslContext().getSocketFactory(), TlsUtils.getTrustManager())
                    .addInterceptor(chain -> {
                        Request.Builder reqBuilder = chain.request().newBuilder();
                        RequestContextPropagation.populateResponse(
                                (key, value) -> reqBuilder.header(key, String.valueOf(value)));
                        reqBuilder.header("X-Origin-Namespace", namespace);
                        reqBuilder.header("authorization", "Basic " + token);
                        return chain.proceed(reqBuilder.build());
                    })
                    .build();
            FieldUtils.writeField(httpClient, "httpClient", client, true);
            return httpClient;
        } catch (Exception ex) {
            log.error("Failed to create MaaS REST Client", ex);
            throw ex;
        }
    }

    @Bean
    KafkaMaaSClient kafkaMaaSClient(MaaSAPIClient maaSClient) {
        return maaSClient.getKafkaClient();
    }


    @Bean
    RabbitMaaSClient rabbitMaaSClient(MaaSAPIClient maaSClient) {
        return maaSClient.getRabbitClient();
    }
}

package org.qubership.integration.platform.ai.llm.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.model.openai.OpenAiResponsesChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesStreamingChatModel;
import io.quarkiverse.langchain4j.ModelBuilderCustomizer;
import io.quarkiverse.langchain4j.ModelName;
import io.quarkiverse.langchain4j.jaxrsclient.JaxRsHttpClientBuilder;
import io.quarkiverse.langchain4j.openai.runtime.config.LangChain4jOpenAiConfig;
import io.quarkus.tls.TlsConfiguration;
import io.quarkus.tls.TlsConfigurationRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.ClientRequestFilter;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.Optional;
import org.jboss.resteasy.reactive.client.impl.ClientRequestContextImpl;
import org.qubership.integration.platform.ai.configuration.AppConfig;

/** Configures provider routing and the timeout for Responses HTTP exchanges. */
@ApplicationScoped
public class ResponsesDeadlineCustomizer {

  private final LangChain4jOpenAiConfig config;
  private final TlsConfigurationRegistry tlsRegistry;
  private final Optional<String> openRouterProvider;
  private final ObjectMapper mapper;

  @Inject
  public ResponsesDeadlineCustomizer(
      LangChain4jOpenAiConfig config, TlsConfigurationRegistry tlsRegistry,
      AppConfig appConfig, ObjectMapper mapper) {
    this.config = config;
    this.tlsRegistry = tlsRegistry;
    this.openRouterProvider = appConfig.llm().openRouterProvider();
    this.mapper = mapper;
  }

  @Produces
  @ModelName(RateLimitChatModelProducer.OPENAI_UPSTREAM_MODEL_NAME)
  ModelBuilderCustomizer<OpenAiResponsesChatModel.Builder> chatCustomizer() {
    return builder -> builder.httpClientBuilder(httpClientBuilder());
  }

  @Produces
  @ModelName(RateLimitChatModelProducer.OPENAI_UPSTREAM_MODEL_NAME)
  ModelBuilderCustomizer<OpenAiResponsesStreamingChatModel.Builder> streamingCustomizer() {
    return builder -> builder.httpClientBuilder(httpClientBuilder());
  }

  private JaxRsHttpClientBuilder httpClientBuilder() {
    var named = config.namedConfig().get(RateLimitChatModelProducer.OPENAI_UPSTREAM_MODEL_NAME);
    Duration timeout = named.timeout().orElse(Duration.ofSeconds(10));
    var builder = new JaxRsHttpClientBuilder();
    builder.connectTimeout(timeout);
    builder.readTimeout(timeout);
    TlsConfiguration.from(tlsRegistry, named.tlsConfigurationName()).ifPresent(builder::tlsConfiguration);
    builder.addClientProvider(deadlineFilter(timeout));
    openRouterProvider.filter(provider -> !provider.isBlank())
        .ifPresent(provider -> builder.addClientProvider(providerFilter(provider, mapper)));
    return builder;
  }

  static ClientRequestFilter providerFilter(String provider, ObjectMapper mapper) {
    return request -> {
      if (!"openrouter.ai".equalsIgnoreCase(request.getUri().getHost())
          || !request.getUri().getPath().endsWith("/responses")) {
        return;
      }
      ObjectNode payload = (ObjectNode) mapper.readTree((String) request.getEntity());
      payload.putObject("provider").put("allow_fallbacks", false).putArray("only").add(provider);
      request.setEntity(mapper.writeValueAsString(payload));
    };
  }

  static ClientRequestFilter deadlineFilter(Duration deadline) {
    return request -> {
      var context = ((ClientRequestContextImpl) request).getRestClientRequestContext();
      var result = context.getResult();
      result.copy().orTimeout(deadline.toMillis(), TimeUnit.MILLISECONDS).whenComplete((response, failure) -> {
        if (failure instanceof TimeoutException && result.completeExceptionally(
            new HttpTimeoutException("LLM_REQUEST_DEADLINE_EXCEEDED: " + deadline))) {
          context.setUserCanceled();
          var connection = context.getHttpClientRequest();
          if (connection != null) {
            connection.reset();
          }
        }
      });
    };
  }
}

package org.qubership.integration.platform.engine.routes.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.quarkiverse.mcp.server.CompletionResponse;
import io.quarkiverse.mcp.server.DefaultValueConverter;
import io.quarkiverse.mcp.server.InitialCheck;
import io.quarkiverse.mcp.server.McpServer;
import io.quarkiverse.mcp.server.PromptResponse;
import io.quarkiverse.mcp.server.ResourceResponse;
import io.quarkiverse.mcp.server.ToolFilter;
import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkiverse.mcp.server.http.runtime.config.McpHttpServersBuildTimeConfig;
import io.quarkiverse.mcp.server.runtime.FeatureMetadata;
import io.quarkiverse.mcp.server.runtime.McpMetadata;
import io.quarkiverse.mcp.server.runtime.McpObjectMapperCustomizer;
import io.quarkus.vertx.runtime.jackson.QuarkusJacksonJsonCodec;
import io.smallrye.common.annotation.Identifier;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class McpTriggerSnapshotDependencies {
    @Inject
    McpHttpServersBuildTimeConfig httpConfig;

    @Produces
    @ApplicationScoped
    public Vertx vertx() {
        return Vertx.vertx(new VertxOptions().setEventLoopPoolSize(2).setWorkerPoolSize(8));
    }

    public void closeVertx(@Disposes Vertx vertx) throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Produces
    @ApplicationScoped
    public ObjectMapper objectMapper(@Identifier("jsonMapper") ObjectMapper mapper) {
        ObjectMapper protocolMapper = mapper.copy();
        // The native SSE handler writes each encoded message into one data line.
        protocolMapper.disable(SerializationFeature.INDENT_OUTPUT);
        new McpObjectMapperCustomizer().customize(protocolMapper);
        return protocolMapper;
    }

    @Produces
    @ApplicationScoped
    public InitialCheck initialCheck() {
        // The component extension's mock cannot represent an absent optional initialization policy.
        return request -> InitialCheck.CheckResult.successs();
    }

    @Produces
    @ApplicationScoped
    public ToolFilter toolVisibility() {
        return (tool, connection) -> true;
    }

    @PreDestroy
    public void restoreJsonCodec() {
        QuarkusJacksonJsonCodec.reset();
    }

    public McpHttpServersBuildTimeConfig httpBuildTimeConfig() {
        return httpConfig;
    }

    @Produces
    @ApplicationScoped
    public McpMetadata metadata() {
        return new McpMetadata() {
            @Override
            public Set<String> serverNames() {
                return Set.of(McpServer.DEFAULT);
            }

            @Override
            public List<FeatureMetadata<PromptResponse>> prompts() {
                return List.of();
            }

            @Override
            public List<FeatureMetadata<CompletionResponse>> promptCompletions() {
                return List.of();
            }

            @Override
            public List<FeatureMetadata<ToolResponse>> tools() {
                return List.of();
            }

            @Override
            public List<FeatureMetadata<ResourceResponse>> resources() {
                return List.of();
            }

            @Override
            public List<FeatureMetadata<ResourceResponse>> resourceTemplates() {
                return List.of();
            }

            @Override
            public List<FeatureMetadata<CompletionResponse>> resourceTemplateCompletions() {
                return List.of();
            }

            @Override
            public List<FeatureMetadata<Void>> notifications() {
                return List.of();
            }

            @Override
            public boolean isPromptManagerUsed() {
                return false;
            }

            @Override
            public boolean isToolManagerUsed() {
                return true;
            }

            @Override
            public boolean isResourceManagerUsed() {
                return false;
            }

            @Override
            public boolean isResourceTemplateManagerUsed() {
                return false;
            }

            @Override
            public Map<Type, DefaultValueConverter<?>> defaultValueConverters() {
                return Map.of();
            }

            @Override
            public Map<String, Class<?>> toolArgumentHolders() {
                return Map.of();
            }
        };
    }
}

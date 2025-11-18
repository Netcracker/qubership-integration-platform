package org.qubership.integration.platform.engine.consul;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.common.annotation.Identifier;
import io.vertx.ext.consul.KeyValueOptions;
import io.vertx.mutiny.ext.consul.ConsulClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.qubership.integration.platform.engine.model.deployment.engine.EngineInfo;
import org.qubership.integration.platform.engine.model.deployment.engine.EngineState;

import java.util.UUID;

import static java.util.Objects.isNull;

@Slf4j
@ApplicationScoped
public class EngineStateService {
    private static final String LOCALDEV_NODE_ID = "-" + UUID.randomUUID();

    @ConfigProperty(name = "consul.keys.prefix")
    String keyPrefix;

    @ConfigProperty(name = "consul.keys.engine-config-root")
    String keyEngineConfigRoot;

    @ConfigProperty(name = "consul.keys.engines-state")
    String keyEnginesState;

    @ConfigProperty(name = "consul.dynamic-state-keys.enabled", defaultValue = "false")
    boolean dynamicStateKeys;

    @Inject
    ConsulClient consulClient;

    @Inject
    ConsulSessionService consulSessionService;

    @Inject
    EngineInfo engineInfo;

    @Inject
    @Identifier("jsonMapper")
    ObjectMapper objectMapper;

    public void updateState(EngineState state) {
        log.debug("Update engines state");
        String sessionId = consulSessionService.getOrCreateSession();
        if (isNull(sessionId)) {
            throw new RuntimeException("Active consul session is not present");
        }
        String key = getConsulKey();
        createOrUpdateKVWithSession(key, state, sessionId);
    }

    private String getConsulKey() {
        return keyPrefix + keyEngineConfigRoot + keyEnginesState
                + "/" + engineInfo.getEngineDeploymentName()
                + "-" + engineInfo.getDomain()
                + "-" + engineInfo.getHost()
                + (dynamicStateKeys ? LOCALDEV_NODE_ID : "");
    }

    private void createOrUpdateKVWithSession(String key, EngineState state, String sessionId) {
        KeyValueOptions options = new KeyValueOptions()
                .setAcquireSession(sessionId);
        try {
            String value = objectMapper.writeValueAsString(state);
            consulClient.putValueWithOptions(key, value, options)
                    .onFailure()
                    .transform(failure -> {
                        log.error("Failed to create or update KV in consul: {}", failure.getMessage());
                        return failure;
                    })
                    .await()
                    .indefinitely();
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize value: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }
}

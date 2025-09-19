package org.qubership.integration.platform.engine.consul.updates.parsers;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.common.annotation.Identifier;
import io.vertx.ext.consul.KeyValue;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.qubership.integration.platform.engine.model.deployment.properties.DeploymentRuntimeProperties;
import org.qubership.integration.platform.engine.service.debugger.RuntimePropertiesException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static java.util.Objects.isNull;

@Slf4j
@ApplicationScoped
public class ChainRuntimePropertiesUpdateParser implements Function<List<KeyValue>, Map<String, DeploymentRuntimeProperties>> {
    @ConfigProperty(name = "consul.keys.runtime-configurations")
    String keyRuntimeConfigurations;

    @Inject
    @Identifier("jsonMapper")
    ObjectMapper objectMapper;

    @Override
    public Map<String, DeploymentRuntimeProperties> apply(List<KeyValue> entries) {
        Map<String, DeploymentRuntimeProperties> result = new HashMap<>();
        boolean failed = false;

        for (KeyValue kv : entries) {
            String chainId = parseChainId(kv.getKey());
            if (isNull(chainId)) {
                throw new RuntimePropertiesException(
                        "Failed to parse response, invalid 'key' field: "
                                + kv.getKey());
            }

            String decodedValue = DecodeUtil.decodeValue(kv.getValue());
            try {
                DeploymentRuntimeProperties properties = objectMapper.readValue(
                        decodedValue, DeploymentRuntimeProperties.class);
                result.put(chainId, properties);
            } catch (Exception e) {
                log.warn("Failed to deserialize runtime properties update for chain: {}, error: {}",
                        chainId, e.getMessage());
                failed = true;
            }
        }

        if (failed) {
            throw new RuntimePropertiesException(
                    "Failed to deserialize consul response"
                            + " for one or more chains");
        }

        return result;
    }

    private String parseChainId(String k) {
        String[] keys = k.split("/");
        int keyIndex = getKeyIndex(keys, keyRuntimeConfigurations);
        int chainIdTargetIndex = keyIndex + 2;
        boolean keyIsValid = keyIndex != -1
                && keys.length > chainIdTargetIndex
                && StringUtils.isNotEmpty(keys[chainIdTargetIndex]);
        return keyIsValid ? keys[chainIdTargetIndex] : null;
    }

    private static int getKeyIndex(String[] keys, String targetKey) {
        int startIndex = -1;
        for (int i = 0; i < keys.length; i++) {
            String key = keys[i];
            if (("/" + key).equals(targetKey)) {
                startIndex = i;
                break;
            }
        }
        return startIndex;
    }
}

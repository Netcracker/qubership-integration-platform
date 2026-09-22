package org.qubership.integration.platform.engine.consul.updates.parsers;

import io.vertx.ext.consul.KeyValue;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@ApplicationScoped
public class CommonVariablesUpdateParser implements Function<List<KeyValue>, Map<String, String>> {
    @ConfigProperty(name = "consul.keys.prefix")
    String keyPrefix;

    @ConfigProperty(name = "consul.keys.engine-config-root")
    String keyEngineConfigRoot;

    @ConfigProperty(name = "consul.keys.common-variables-v2")
    String keyCommonVariablesV2;

    @Override
    public Map<String, String> apply(List<KeyValue> entries) {
        // must match the key UpdateGetterProducer#commonVariablesUpdateGetter() queries,
        // so that a variable entry directly under it is recognized as an L1 path
        String commonVariablesPath = keyPrefix + keyEngineConfigRoot + keyCommonVariablesV2;
        return entries.stream()
                .filter(kv -> hasL1NonEmptyPath(commonVariablesPath, kv.getKey()))
                .map(CommonVariablesUpdateParser::parseCommonVariable)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toMap(Pair::getKey, Pair::getValue));
    }

    private static Optional<Pair<String, String>> parseCommonVariable(KeyValue kv) {
        String[] split = kv.getKey().split("/");
        if (split.length == 0) {
            log.warn("Can't parse common variable from key-value pair: {}", kv);
            return Optional.empty();
        }
        String key = split[split.length - 1];
        String value = StringUtils.isBlank(kv.getValue()) ? "" : kv.getValue();
        return Optional.of(Pair.of(key, value));
    }

    private static boolean hasL1NonEmptyPath(String pathPrefix, String path) {
        String remainder = path.substring(pathPrefix.length());
        // pathPrefix (consul.keys.prefix + engine-config-root + common-variables-v2) has no
        // trailing slash, so the remainder starts with the "/" separating it from the variable
        // name; strip it before splitting, otherwise every remainder has a leading empty segment
        if (remainder.startsWith("/")) {
            remainder = remainder.substring(1);
        }
        String[] split = remainder.split("/");
        return split.length == 1 && StringUtils.isNotEmpty(split[0]);
    }
}

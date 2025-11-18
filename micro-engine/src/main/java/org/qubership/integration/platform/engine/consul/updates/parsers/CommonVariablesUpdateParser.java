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

    @Override
    public Map<String, String> apply(List<KeyValue> entries) {
        return entries.stream()
                .filter(kv -> hasL1NonEmptyPath(keyPrefix, kv.getKey()))
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
        String[] split = path.substring(pathPrefix.length()).split("/");
        return split.length == 1 && StringUtils.isNotEmpty(split[0]);
    }
}

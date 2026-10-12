package org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.chain;

import org.qubership.integration.platform.io.model.exportimport.chain.ChainElementExternalEntity;

import java.util.List;
import java.util.Map;

public interface ElementResourceFileNameBuilder {

    String generatePropertiesFileName(ChainElementExternalEntity externalElement, List<String> propsToExportInSeparateFile);

    String generateAfterScriptFileName(String id, Map<String, Object> afterProp);

    String generateBeforeScriptFileName(String id);

    String generateAfterMapperFileName(String id, Map<String, Object> afterProp);

    String generateBeforeMapperFileName(String id);

    default String normalizeAfterId(Object idOrCode) {
        String value = String.valueOf(idOrCode);
        if (value.matches("^[1-5]00\\.\\.[1-5]99$") && value.charAt(0) == value.charAt(5)) {
            return value.charAt(0) + "xx";
        }
        return value;
    }

    default Object getIdOrCode(Map<String, Object> mapProp) {
        Object id = mapProp.get("id");
        return id != null ? id : mapProp.get("code");
    }
}

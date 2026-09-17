package org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.chain;

import org.qubership.integration.platform.io.model.exportimport.chain.ChainElementExternalEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.qubership.integration.platform.io.model.exportimport.ExportImportConstants.*;

@Component
@ConditionalOnProperty(name = "qip.export.legacy-resource-names", havingValue = "true")
public class OldElementResourceFileNameBuilder implements ElementResourceFileNameBuilder {

    @Override
    public String generatePropertiesFileName(ChainElementExternalEntity externalElement, List<String> propsToExportInSeparateFile) {
        String prefix;
        if (externalElement.getType() != null && externalElement.getType().startsWith(MAPPER)) {
            prefix = propsToExportInSeparateFile.size() == 1 ? propsToExportInSeparateFile.get(0) : "mapper";
        } else {
            prefix = propsToExportInSeparateFile.size() == 1 ? propsToExportInSeparateFile.get(0) : "properties";
        }
        String extension = Optional.ofNullable(externalElement.getProperties().get(EXPORT_FILE_EXTENSION_PROPERTY))
                .map(Object::toString)
                .map(e -> e.startsWith(".") ? e.substring(1) : e)
                .orElse(DEFAULT_EXTENSION.startsWith(".") ? DEFAULT_EXTENSION.substring(1) : DEFAULT_EXTENSION);
        return prefix + "-" + externalElement.getId() + "." + extension;
    }

    @Override
    public String generateAfterScriptFileName(String id, Map<String, Object> afterProp) {
        return SCRIPT + DASH + getIdOrCode(afterProp) + DASH + id + "." + GROOVY_EXTENSION;
    }

    @Override
    public String generateBeforeScriptFileName(String id) {
        return SCRIPT + DASH + BEFORE + DASH + id + "." + GROOVY_EXTENSION;
    }

    @Override
    public String generateAfterMapperFileName(String id, Map<String, Object> afterProp) {
        return MAPPING_DESCRIPTION + DASH + getIdOrCode(afterProp) + DASH + id + "." + JSON_EXTENSION;
    }

    @Override
    public String generateBeforeMapperFileName(String id) {
        return MAPPING_DESCRIPTION + DASH + BEFORE + DASH + id + "." + JSON_EXTENSION;
    }
}

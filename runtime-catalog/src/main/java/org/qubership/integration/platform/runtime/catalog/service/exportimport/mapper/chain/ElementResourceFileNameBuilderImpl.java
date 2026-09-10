package org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.chain;

import org.qubership.integration.platform.io.model.exportimport.chain.ChainElementExternalEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.qubership.integration.platform.io.model.exportimport.ExportImportConstants.*;

@Component
@ConditionalOnProperty(name = "qip.export.legacy-resource-names", havingValue = "false", matchIfMissing = true)
public class ElementResourceFileNameBuilderImpl implements ElementResourceFileNameBuilder {

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
        String kind;
        if (MAPPING_DESCRIPTION.equals(prefix)) {
            kind = MAPPER;
        } else if (SCRIPT.equals(prefix)) {
            kind = SCRIPT;
        } else {
            kind = prefix;
        }
        return externalElement.getId() + ".element." + kind + ".cip." + extension;
    }

    @Override
    public String generateAfterScriptFileName(String id, Map<String, Object> afterProp) {
        return id + ".after-" + normalizeAfterId(getIdOrCode(afterProp)) + ".script.cip." + GROOVY_EXTENSION;
    }

    @Override
    public String generateBeforeScriptFileName(String id) {
        return id + ".before.script.cip." + GROOVY_EXTENSION;
    }

    @Override
    public String generateAfterMapperFileName(String id, Map<String, Object> afterProp) {
        return id + ".after-" + normalizeAfterId(getIdOrCode(afterProp)) + ".mapper.cip." + JSON_EXTENSION;
    }

    @Override
    public String generateBeforeMapperFileName(String id) {
        return id + ".before.mapper.cip." + JSON_EXTENSION;
    }
}

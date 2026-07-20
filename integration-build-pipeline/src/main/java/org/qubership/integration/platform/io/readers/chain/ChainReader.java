/*
 * Copyright 2024-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.qubership.integration.platform.io.readers.chain;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.qubership.integration.platform.chain.model.ImportChain;
import org.qubership.integration.platform.io.model.exportimport.chain.ChainExternalEntity;
import org.qubership.integration.platform.io.readers.migrations.FileMigrationService;
import org.qubership.integration.platform.io.readers.migrations.ImportFileMigration;
import org.qubership.integration.platform.io.readers.migrations.chain.ChainImportFileMigration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.util.Collection;
import java.util.List;

/**
 * Reads a chain from an exported chain directory into the library {@link ImportChain} model.
 *
 * <p>A chain directory holds the main chain YAML plus any separately exported property files. The
 * reader locates the YAML, migrates it to the current file version, deserializes it, restores the
 * file-backed element properties, and maps the result to the library model.
 */
@Component
public class ChainReader {

    private final YAMLMapper yamlMapper;
    private final FileMigrationService fileMigrationService;
    private final Collection<ChainImportFileMigration> chainImportFileMigrations;
    private final ChainModelMapper chainModelMapper;

    public ChainReader(
            @Qualifier("defaultYamlMapper") YAMLMapper yamlMapper,
            FileMigrationService fileMigrationService,
            Collection<ChainImportFileMigration> chainImportFileMigrations,
            ChainModelMapper chainModelMapper
    ) {
        this.yamlMapper = yamlMapper;
        this.fileMigrationService = fileMigrationService;
        this.chainImportFileMigrations = chainImportFileMigrations;
        this.chainModelMapper = chainModelMapper;
    }

    /**
     * Reads the chain stored in {@code chainDir}.
     *
     * @param chainDir directory that contains the chain YAML and its property files
     * @throws IllegalArgumentException if the directory has no chain YAML or the content cannot be read
     */
    public ImportChain read(File chainDir) {
        try {
            String chainYaml = Files.readString(getChainYamlFile(chainDir).toPath());
            chainYaml = migrateToCurrentFileVersion(chainYaml);
            ChainExternalEntity externalEntity = yamlMapper.readValue(chainYaml, ChainExternalEntity.class);
            return toModel(externalEntity, new DirectoryPropertyFileSource(chainDir));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to read chain from directory " + chainDir.getName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Maps an already deserialized external chain to the library model. Package-visible so tests can
     * exercise the mapping without a chain directory; pass {@code null} for {@code fileSource} to skip
     * property-file substitution.
     */
    ImportChain toModel(ChainExternalEntity externalEntity, @Nullable PropertyFileSource fileSource) {
        return chainModelMapper.map(externalEntity, fileSource);
    }

    private String migrateToCurrentFileVersion(String chainYaml) throws Exception {
        List<ImportFileMigration> migrations = chainImportFileMigrations.stream()
                .map(ImportFileMigration.class::cast)
                .toList();
        return fileMigrationService.migrate(chainYaml, migrations);
    }

    private File getChainYamlFile(File chainDir) {
        File[] chainFiles = chainDir.listFiles((dir, name) ->
                (name.endsWith(".yaml") || name.endsWith(".yml"))
                        && (name.startsWith("chain-") || name.contains(".chain.")));

        if (chainFiles == null || chainFiles.length == 0) {
            throw new IllegalArgumentException("Directory " + chainDir.getName() + " does not contain a chain YAML file");
        }

        return chainFiles[0];
    }
}

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

package org.qubership.integration.platform.runtime.catalog.service.exportimport;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.io.File;
import java.util.regex.Pattern;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ExportImportConstants {
    public static final String ZIP_EXTENSION = "zip";
    public static final String YAML_EXTENSION = "yaml";
    public static final String GROOVY_EXTENSION = "groovy";
    public static final String JSON_EXTENSION = "json";
    public static final String RESOURCES_FOLDER_PREFIX = "resources" + File.separator;
    @Deprecated
    public static final String CHAIN_YAML_NAME_PREFIX = "chain-";
    public static final String CHAIN_YAML_NAME_POSTFIX = ".chain.";
    @Deprecated
    public static final String SERVICE_YAML_NAME_PREFIX = "service-";
    public static final String SERVICE_YAML_NAME_POSTFIX = ".service.";
    public static final String SOURCE_YAML_NAME_PREFIX =  "source-";
    public static final String EXPORT_FILE_NAME_PREFIX = "export-";
    @Deprecated
    public static final String SPECIFICATION_GROUP_FILE_PREFIX = "specGroup-";
    public static final String SPECIFICATION_GROUP_FILE_POSTFIX = ".specification-group.";
    @Deprecated
    public static final String SPECIFICATION_FILE_PREFIX = "specification-";
    public static final String SPECIFICATION_FILE_POSTFIX = ".specification.";
    public static final String YAML_FILE_NAME_POSTFIX = ".yaml";
    public static final String JSON_FILE_NAME_POSTFIX = ".json";
    public static final String API_SPECIFICATION_SUFFIX = "-specification-";
    public static final Pattern YAML_FILE_EXTENSION_REGEXP = Pattern.compile("yaml|yml", Pattern.CASE_INSENSITIVE);

    public static final String API_SPECIFICATION_TITLE = "Exported API specification";
    public static final String API_SPECIFICATION_VERSION_PREFIX = "exported-api-specification-";
    public static final String ZIP_NAME_POSTFIX = ".zip";
    public static final String EXPORT_FILE_EXTENSION_PROPERTY = "exportFileExtension";
    public static final String PROPS_EXPORT_IN_SEPARATE_FILE_PROPERTY = "propertiesToExportInSeparateFile";
    public static final String DEFAULT_EXTENSION = ".txt";
    public static final String DATE_TIME_FORMAT_PATTERN = "yyyy-MM-dd'T'HH_mm_ss";
    public static final String CHAINS_ARCH_PARENT_DIR = "chains";
    public static final String AFTER = "after";
    public static final String SCRIPT = "script";
    public static final String SERVICE_CALL = "service-call";
    public static final String TYPE = "type";
    public static final String BEFORE = "before";
    public static final String DASH = "-";
    public static final String SCRIPT_SEPARATOR = "::";
    public static final String SQL_EXTENSION = "sql";
    public static final String MAPPER = "mapper";
    public static final String MAPPING_DESCRIPTION = "mappingDescription";
    public static final String MAPPING = "mapping";
    public static final String SOURCE = "source";
    public static final String TARGET = "target";
    public static final String DIFFERENT_PROTOCOL_ERROR_MESSAGE = "Protocol of provided specification doesn't match with a system protocol";
    public static final String INVALID_INPUT_FILE_ERROR_MESSAGE = "Input file is invalid";
    public static final String NO_SPECIFICATION_SOURCE_ERROR_MESSAGE = "Can't find specification source";
    public static final String NO_MAIN_SOURCE_ERROR_MESSAGE = "Can't find Main specification source";
    public static final String MULTIPLE_MAIN_SOURCES_ERROR_MESSAGE = "Multiple Main specification sources found";
    public static final String FILE_CREATION_ERROR_MESSAGE = "Unknown exception during file creation: ";
    public static final String ARCH_PARENT_DIR = "services";
    public static final String SAVED_WITHOUT_SNAPSHOT_ERROR_MESSAGE = "Chain is saved but without snapshot: ";
    public static final String SAVED_WITHOUT_DEPLOYMENT_ERROR_MESSAGE = "Chain is saved but not deployed: ";
    public static final String OVERRIDDEN_LABEL_NAME = "Overridden";
    public static final String OVERRIDES_LABEL_NAME = "Overrides";


    //********************************************\\
    //************ Serdes constants **************\\
    //********************************************\\

    public static final String ID = "id";
    public static final String NAME = "name";
    public static final String DESCRIPTION = "description";
    public static final String ELEMENT_TYPE = "element-type";
    public static final String SWIMLANE_ID = "swimlane-id";
    public static final String FILE_NAME_PROPERTY = "propertiesFilename";
    public static final String PROPERTIES = "properties";
    public static final String CHILDREN = "children";
    public static final String MASKING_ENABLED = "maskingEnabled";
    public static final String DEFAULT_SWIMLANE_ID = "default-swimlane-id";
    public static final String REUSE_SWIMLANE_ID = "reuse-swimlane-id";
    public static final String MASKED_FIELDS = "maskedFields";
    public static final String ELEMENTS = "elements";
    public static final String DEPENDENCIES = "dependencies";
    public static final String FOLDER = "folder";
    public static final String DEPLOYMENTS = "deployments";
    public static final String MODIFIED_WHEN = "modifiedWhen";
    public static final String FROM = "from";
    public static final String TO = "to";
    public static final String DEPLOY_ACTION = "deployAction";
    public static final String CODE = "code";
    public static final String LABELS = "labels";
    public static final String BUSINESS_DESCRIPTION = "businessDescription";
    public static final String ASSUMPTIONS = "assumptions";
    public static final String OUT_OF_SCOPE = "outOfScope";
}

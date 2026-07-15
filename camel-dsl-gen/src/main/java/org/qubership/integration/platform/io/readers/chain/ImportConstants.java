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

import java.io.File;

/**
 * Property names and file-extension tokens shared by the chain import reader. They mirror
 * {@code ExportImportConstants} so the reader restores element properties the same way the
 * exporter wrote them.
 */
final class ImportConstants {

    static final String CONTAINER = "container";
    static final String SERVICE_CALL = "service-call";

    static final String FILE_NAME_PROPERTY = "propertiesFilename";
    static final String PROPS_EXPORT_IN_SEPARATE_FILE_PROPERTY = "propertiesToExportInSeparateFile";
    static final String EXPORT_FILE_EXTENSION_PROPERTY = "exportFileExtension";

    static final String AFTER = "after";
    static final String BEFORE = "before";
    static final String TYPE = "type";
    static final String SCRIPT = "script";
    static final String MAPPER = "mapper";
    static final String MAPPING = "mapping";
    static final String MAPPING_DESCRIPTION = "mappingDescription";
    static final String SOURCE = "source";
    static final String TARGET = "target";

    static final String JSON_EXTENSION = "json";
    static final String GROOVY_EXTENSION = "groovy";
    static final String SQL_EXTENSION = "sql";

    static final String RESOURCES_FOLDER_PREFIX = "resources" + File.separator;

    private ImportConstants() {
    }
}

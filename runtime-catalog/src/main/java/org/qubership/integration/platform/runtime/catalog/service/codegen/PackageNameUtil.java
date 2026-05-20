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

package org.qubership.integration.platform.runtime.catalog.service.codegen;

import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationGroup;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;

public class PackageNameUtil {
    public static String buildPackageName(String basePackage, SystemModel model) {
        SpecificationGroup specificationGroup = model.getSpecificationGroup();
        IntegrationSystem system = specificationGroup.getSystem();
        String systemPackageName = buildPackageName(system.getName(), "system", "system_");
        String groupPackageName = buildPackageName(specificationGroup.getName(), "group", "group_");
        String modelPackageName = buildPackageName(model.getName(), "model", "model_");
        return String.join(".", basePackage, systemPackageName, groupPackageName, modelPackageName);
    }

    public static String buildPackageName(String name, String defaultName, String prefix) {
        String packageName = StringUtils.strip(name.replaceAll("([^\\w\\d]|_)+", "_").toLowerCase(), "_");
        return StringUtils.isBlank(packageName)
                ? defaultName
                : Character.isAlphabetic(packageName.charAt(0)) ? packageName : prefix + packageName;
    }

    private PackageNameUtil() {
    }
}

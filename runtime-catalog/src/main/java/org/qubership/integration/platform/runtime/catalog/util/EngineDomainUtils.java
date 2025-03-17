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

package org.qubership.integration.platform.runtime.catalog.util;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class EngineDomainUtils {
    private final String DASH_VERSION_REGEX = "-v\\d+$";
    private final Pattern DEFAULT_VERSIONED_DOMAIN_PATTERN;

    @Value("${qip.domain.default}")
    private String engineDefaultDomain;

    @Autowired
    public EngineDomainUtils(@Value("${qip.internal-services.engine}") String engineNamePrefix) {
        DEFAULT_VERSIONED_DOMAIN_PATTERN = Pattern.compile("^" + engineNamePrefix + DASH_VERSION_REGEX);
    }

    public String convertKubeDeploymentToDomainName(String deploymentName) {
        boolean isDefault = DEFAULT_VERSIONED_DOMAIN_PATTERN.matcher(deploymentName).find();

        return isDefault ?
                engineDefaultDomain :
                deploymentName.replaceFirst(DASH_VERSION_REGEX, "");
    }
}

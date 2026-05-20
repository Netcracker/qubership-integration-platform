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

package org.qubership.integration.platform.engine.security;

import org.springframework.security.access.ConfigAttribute;
import org.springframework.security.access.SecurityConfig;

import java.util.Collection;
import java.util.List;

import static java.util.stream.Collectors.toList;

/**
    Warning: Class is specified in http trigger template.hbs file by full name (with package).
    Do not move this class.
 */
public class QipSecurityAccessPolicy {
    private final List<ConfigAttribute> configAttributes;

    public QipSecurityAccessPolicy(List<ConfigAttribute> configAttributes) {
        this.configAttributes = configAttributes;
    }

    public List<ConfigAttribute> getConfigAttributes() {
        return configAttributes;
    }

    public static QipSecurityAccessPolicy fromStrings(Collection<String> strings) {
        List<ConfigAttribute> attributes = strings.stream().map(SecurityConfig::new).collect(toList());
        return new QipSecurityAccessPolicy(attributes);
    }
}

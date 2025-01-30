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

package org.qubership.integration.platform.runtime.catalog.consul;

import org.springframework.boot.context.config.ConfigDataLocation;
import org.springframework.boot.context.config.ConfigDataLocationResolverContext;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.cloud.consul.config.ConsulConfigDataLocationResolver;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponents;

@Order(Ordered.HIGHEST_PRECEDENCE)
public class ConsulUrlWithSchemaDataLocationResolver extends ConsulConfigDataLocationResolver {
    protected ConsulUrlWithSchemaDataLocationResolver(DeferredLogFactory log) {
        super(log);
    }

    @Nullable
    @Override
    protected UriComponents parseLocation(ConfigDataLocationResolverContext context, ConfigDataLocation location) {
        String originalLocation = location.getNonPrefixedValue(PREFIX);
        if (!StringUtils.hasText(originalLocation)) {
            return null;
        }
        return super.parseLocation(context, ConfigDataLocation.of(PREFIX + originalLocation.replaceAll("^.+//", "")));
    }
}

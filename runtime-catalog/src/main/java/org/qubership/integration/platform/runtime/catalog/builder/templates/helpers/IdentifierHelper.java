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

package org.qubership.integration.platform.runtime.catalog.builder.templates.helpers;

import com.github.jknack.handlebars.Context;
import com.github.jknack.handlebars.Helper;
import com.github.jknack.handlebars.Options;
import org.qubership.integration.platform.runtime.catalog.builder.templates.TemplatesHelper;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;

import java.io.IOException;
import java.util.Optional;

@TemplatesHelper("identifier")
public class IdentifierHelper implements Helper<Object> {
    private static final String PREFIX = "prefix";
    private static final String SUFFIX = "suffix";

    @Override
    public Object apply(Object context, Options options) throws IOException {
        if (!(context instanceof ChainElement)) {
            Context parentContext = options.context.parent();
            if (parentContext != null) {
                context = parentContext.model();
            }
        }

        if (context instanceof ChainElement element) {
            String prefix = getOption(options, PREFIX);
            String suffix = getOption(options, SUFFIX);
            return prefix + element.getId() + suffix;
        }
        return null;
    }

    private static String getOption(Options options, String key) {
        return Optional.ofNullable(options.hash.get(key))
                .map(Object::toString)
                .orElse("");
    }
}

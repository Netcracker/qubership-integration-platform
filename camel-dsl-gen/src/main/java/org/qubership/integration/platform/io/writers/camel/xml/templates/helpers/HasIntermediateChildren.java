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

package org.qubership.integration.platform.io.writers.camel.xml.templates.helpers;

import com.github.jknack.handlebars.Helper;
import com.github.jknack.handlebars.Options;
import org.qubership.integration.platform.chain.model.Element;
import org.qubership.integration.platform.io.writers.camel.xml.templates.TemplatesHelper;

import java.io.IOException;

import static org.qubership.integration.platform.library.constants.ConfigurationPropertiesConstants.ELEMENTS_WITH_INTERMEDIATE_CHILDREN;

@TemplatesHelper("has-intermediate-children")
public class HasIntermediateChildren implements Helper<Element> {
    @Override
    public Object apply(Element element, Options options) throws IOException {
        return element != null && ELEMENTS_WITH_INTERMEDIATE_CHILDREN.contains(element.getType());
    }
}

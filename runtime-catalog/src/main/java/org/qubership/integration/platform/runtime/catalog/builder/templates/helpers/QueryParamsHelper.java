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

import com.github.jknack.handlebars.Helper;
import com.github.jknack.handlebars.Options;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.catalog.model.library.ElementDescriptor;
import org.qubership.integration.platform.catalog.model.library.ElementProperty;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.catalog.service.library.LibraryElementsService;
import org.qubership.integration.platform.runtime.catalog.builder.templates.TemplatesHelper;
import org.qubership.integration.platform.runtime.catalog.util.MaasUtils;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static java.util.stream.Collectors.joining;

/**
 * Handlebars helper, which is used for providing values of 'uri' attribute
 * for <to /> xml elements.
 */
@TemplatesHelper("query")
public class QueryParamsHelper extends BaseHelper implements Helper<ChainElement> {

    private final LibraryElementsService libraryElementsService;

    @Autowired
    public QueryParamsHelper(LibraryElementsService libraryElementsService) {
        this.libraryElementsService = libraryElementsService;
    }

    @Override
    public Object apply(ChainElement element, Options options) throws IOException {
        ElementDescriptor descriptor = libraryElementsService.getElementDescriptor(element);
        StringBuilder str = new StringBuilder();
        List<ElementProperty> queryProperties = descriptor.getQueryProperties();
        ArrayList<String> maasEnabledParams = MaasUtils.getMaasParams(element);
        for (ElementProperty property : queryProperties) {
            String value = getPropertyValue(element, property);
            if(!maasEnabledParams.isEmpty() && maasEnabledParams.contains(property.getName())){
                value = MaasUtils.getMaasParamPlaceholder(element.getOriginalId(), property.getName());
                setPropertyValue(str, property, value);
            }else if (StringUtils.isNotBlank(value)) {
                setPropertyValue(str, property, value);
            }
        }
        return str.toString();
    }
    private String getPropertyValue(ChainElement element, ElementProperty property) {
        String value = element.getPropertyAsString(property.getName());
        if (property.isMultiple()) {
            value = Optional.ofNullable(element.getProperty(property.getName()))
                    .filter(Collection.class::isInstance)
                    .map(values -> ((Collection<?>) values).stream().map(String::valueOf).collect(joining(",")))
                    .orElse(value);
        }
        return value;
    }
    private void setPropertyValue(StringBuilder str, ElementProperty property, String value) {
        if (str.length() == 0) {
            str.append("?");
        } else {
            str.append("&");
        }
        str.append(property.getName()).append("=").append(value);
    }
}

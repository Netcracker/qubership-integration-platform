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

import com.github.jknack.handlebars.Options;
import com.github.jknack.handlebars.internal.text.StringEscapeUtils;
import org.qubership.integration.platform.runtime.catalog.builder.templates.TemplatesHelper;
import org.qubership.integration.platform.runtime.catalog.model.constant.ConfigConstants;

import java.util.Map;

@TemplatesHelper
public class PropertyHelperSource extends BaseHelper {
    private static final String OPTION_RAW = "raw";

    /**
     * Method is used to receive ChainElement's sub property with name
     * <code>targetProperty</code> from map with name firstProperty
     * and get it's String value.
     *
     * @param firstProperty Name of source property
     * @param targetProperty Name of property, that must be extracted.
     * @param options      Object, which contains handlebars information.
     * @return Property string value
     */
    public CharSequence subProperty(String firstProperty, String targetProperty, Options options) {
        if (firstProperty == null || targetProperty == null) {
            throw new NullPointerException("propertyName parameter can't be null");
        }

        Map<String, Object> map = (Map<String, Object>) getPropertyValue(firstProperty, options);
        return map == null ? null : (CharSequence) map.get(targetProperty);
    }

    /**
     * Method is used to receive ChainElement's property with name
     * <code>propertyName</code> and get it's String value.
     *
     * @param propertyName Name of property, that must be extracted.
     * @param options      Object, which contains handlebars information.
     * @return Property string value
     */
    public Object property(String propertyName, Options options) {
        if (propertyName == null) {
            throw new NullPointerException("propertyName parameter can't be null");
        }

        boolean isRaw = options.hash.containsKey(OPTION_RAW);
        return isRaw
                ? getPropertyValue(propertyName, options)
                : getPropertyStringValue(propertyName, options);
    }

    /**
     * Method, that wraps String value of input argument into CDATA section
     * and returns final value.
     */
    public CharSequence cdata(Object parameter) {
        if (parameter != null) {
            return ConfigConstants.CDATA
                    + parameter.toString()
                    + ConfigConstants.CDATA_CLOSE;
        }
        return null;
    }

    /**
     * Method, that replaces special chars in input String value with html entities.
     */
    public CharSequence escape(Object value) {
        return value == null ? null : StringEscapeUtils.escapeXml10(value.toString());
    }
}

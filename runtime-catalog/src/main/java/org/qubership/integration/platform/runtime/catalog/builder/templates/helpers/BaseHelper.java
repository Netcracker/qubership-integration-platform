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
import com.github.jknack.handlebars.Options;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.catalog.model.constant.CamelNames;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.util.MaasUtils;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.qubership.integration.platform.catalog.model.constant.CamelOptions.OPERATION_PATH;
import static org.qubership.integration.platform.catalog.model.constant.CamelOptions.TOPICS;

/**
 * Handlebars helper, which provides methods of creating handlebars context
 * based on provided
 */
@Slf4j
public class BaseHelper {

    public String getPropertyStringValue(String propertyName, Options options) {
        var property = getPropertyValue(propertyName, options);
        return property != null ? property.toString() : null;
    }

    public Object getPropertyValue(String propertyName, Options options) {
        var context = options.context.model();

        if(context instanceof ChainElement) {
            var element = (ChainElement) context;
            String elementType = element.getType();
            if (isMaasParamProperty(propertyName, elementType, element) ) {
                return MaasUtils.getMaasParamPlaceholder(element.getOriginalId(), propertyName);
            }
            return element.getProperty(propertyName);
        } else if(context instanceof Map) {
            var map = (Map) context;
            return map.get(propertyName);
        } else {
            throw new IllegalArgumentException("Can't extract property "
                    + propertyName + " from the instance of class: "
                    + context.getClass().toString()
            );
        }
    }

    private static boolean isMaasParamProperty(String propertyName, String elementType, ChainElement element) {
        Map<String, Object> elementProperties = element.getProperties();
        String operationProtocolType = (String) elementProperties.get(CamelNames.OPERATION_PROTOCOL_TYPE_PROP);
        if((isTopicProperty(propertyName, elementType) && isKafkaTrigger2OrKafkaSender2Component(elementType))
                || ((isOperationPathProperty(propertyName, elementType) && isOperationProtocolTypeKafka(operationProtocolType)
                && isServiceCallOrAsyncApiTriggerComponent(elementType)))) {
            List<String> maasParamList = MaasUtils.getMaasParams(element);
            if(!maasParamList.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOperationProtocolTypeKafka(String operationProtocolType) {
        if(StringUtils.isNotEmpty(operationProtocolType) && StringUtils.equalsIgnoreCase(operationProtocolType, CamelNames.OPERATION_PROTOCOL_TYPE_KAFKA)) {
            return  true;
        }
        return false;
    }

    private static boolean isTopicProperty(String propertyName, String elementType) {
        return StringUtils.equalsIgnoreCase(propertyName, TOPICS) && StringUtils.isNotBlank(elementType);
    }

    private static boolean isOperationPathProperty(String propertyName, String elementType) {
        return StringUtils.equalsIgnoreCase(propertyName, OPERATION_PATH) && StringUtils.isNotBlank(elementType);
    }

    private static boolean isKafkaTrigger2OrKafkaSender2Component(final String elementType) {
        return StringUtils.equalsIgnoreCase(elementType, CamelNames.KAFKA_TRIGGER_2_COMPONENT) ||
                StringUtils.equalsIgnoreCase(elementType, CamelNames.KAFKA_SENDER_2_COMPONENT);
    }

    private static boolean isServiceCallOrAsyncApiTriggerComponent(final String elementType) {
        return StringUtils.equalsIgnoreCase(elementType, CamelNames.SERVICE_CALL_COMPONENT) ||
                StringUtils.equalsIgnoreCase(elementType, CamelNames.ASYNC_API_TRIGGER_COMPONENT);
    }

    protected Options.Buffer putCollectionAsContext(Iterable<?> objects, Options options) throws IOException {
        for (var child : objects) {
            putObjectAsChildContext(child, options);
        }
        return options.buffer();
    }

    protected Options.Buffer putObjectAsChildContext(Object object, Options options) throws IOException {
        var parentContext = options.context;
        var buffer = options.buffer();
        var childContext = Context.newContext(parentContext, object);
        var charSequence = options.apply(options.fn, childContext);
        buffer.append(charSequence);
        return buffer;
    }

}

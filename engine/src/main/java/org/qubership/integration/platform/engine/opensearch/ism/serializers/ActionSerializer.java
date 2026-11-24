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

package org.qubership.integration.platform.engine.opensearch.ism.serializers;

import org.qubership.integration.platform.engine.opensearch.ism.model.actions.*;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.introspect.BeanPropertyDefinition;

import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

public class ActionSerializer extends ValueSerializer<Action> {
    private static final Map<Class<? extends Action>, String> CLASS_MAP = Map.ofEntries(
            new AbstractMap.SimpleEntry<Class<? extends Action>, String>(ForceMergeAction.class, "force_merge"),
            new AbstractMap.SimpleEntry<Class<? extends Action>, String>(ReadOnlyAction.class, "read_only"),
            new AbstractMap.SimpleEntry<Class<? extends Action>, String>(ReadWriteAction.class, "read_write"),
            new AbstractMap.SimpleEntry<Class<? extends Action>, String>(ReplicaCountAction.class, "replica_count"),
            new AbstractMap.SimpleEntry<Class<? extends Action>, String>(ShrinkAction.class, "shrink"),
            new AbstractMap.SimpleEntry<Class<? extends Action>, String>(CloseAction.class, "close"),
            new AbstractMap.SimpleEntry<Class<? extends Action>, String>(OpenAction.class, "open"),
            new AbstractMap.SimpleEntry<Class<? extends Action>, String>(DeleteAction.class, "delete"),
            new AbstractMap.SimpleEntry<Class<? extends Action>, String>(RolloverAction.class, "rollover"),
            new AbstractMap.SimpleEntry<Class<? extends Action>, String>(NotificationAction.class, "notification"),
            new AbstractMap.SimpleEntry<Class<? extends Action>, String>(SnapshotAction.class, "snapshot"),
            new AbstractMap.SimpleEntry<Class<? extends Action>, String>(IndexPriorityAction.class, "index_priority"),
            new AbstractMap.SimpleEntry<Class<? extends Action>, String>(AllocationAction.class, "allocation"),
            new AbstractMap.SimpleEntry<Class<? extends Action>, String>(RollupAction.class, "rollup")
    );

    @Override
    public void serialize(Action action, JsonGenerator jsonGenerator, SerializationContext serializerProvider) throws JacksonException {
        jsonGenerator.writeStartObject();
        serializeBaseProperties(action, jsonGenerator, serializerProvider);
        serializeOwnProperties(action, jsonGenerator, serializerProvider);
        jsonGenerator.writeEndObject();
    }

    private void serializeBaseProperties(Action action, JsonGenerator jsonGenerator, SerializationContext serializerProvider) throws JacksonException {
        List<BeanPropertyDefinition> properties = getPropertyDefinitions(Action.class, serializerProvider);
        serializeProperties(action, properties, jsonGenerator, serializerProvider);
    }

    private void serializeOwnProperties(Action action, JsonGenerator jsonGenerator, SerializationContext serializerProvider) throws JacksonException {
        String fieldName = CLASS_MAP.get(action.getClass());
        if (isNull(fieldName)) {
            String message = String.format("Unsupported Action class: %s", action.getClass().getCanonicalName());
            throw DatabindException.from(serializerProvider, message);
        }

        jsonGenerator.writeObjectPropertyStart(fieldName);
        List<BeanPropertyDefinition> properties = getPropertyDefinitions(action.getClass(), serializerProvider).stream()
                .filter(property -> !Action.class.equals(property.getAccessor().getDeclaringClass()))
                .toList();
        serializeProperties(action, properties, jsonGenerator, serializerProvider);
        jsonGenerator.writeEndObject();
    }

    private <T> List<BeanPropertyDefinition> getPropertyDefinitions(Class<T> cls, SerializationContext serializerProvider) {
        JavaType type = serializerProvider.getTypeFactory().constructType(cls);
        BeanDescription description = serializerProvider.introspectBeanDescription(type, null);
        Set<String> ignoredProperties = description.getIgnoredPropertyNames();
        return description.findProperties().stream()
                .filter(definition -> !ignoredProperties.contains(definition.getName()))
                .toList();
    }

    private <T> void serializeProperties(
            T pojo,
            List<BeanPropertyDefinition> properties,
            JsonGenerator jsonGenerator,
            SerializationContext serializerProvider
    ) throws JacksonException {
        for (BeanPropertyDefinition propertyDefinition : properties) {
            try {
                Object value = propertyDefinition.getGetter().callOn(pojo);
                if (nonNull(value)) {
                    Object serializerCls = serializerProvider.getAnnotationIntrospector().findSerializer(
                            serializerProvider.getConfig(), propertyDefinition.getField());
                    if (serializerCls instanceof Class<?> cls) {
                        @SuppressWarnings("unchecked")
                        ValueSerializer<Object> serializer = (ValueSerializer<Object>) cls.getConstructor().newInstance();
                        jsonGenerator.writeName(propertyDefinition.getName());
                        serializer.serialize(value, jsonGenerator, serializerProvider);
                    } else if (serializerCls instanceof ValueSerializer<?> serializer) {
                        jsonGenerator.writeName(propertyDefinition.getName());
                        //noinspection unchecked
                        ((ValueSerializer<Object>) serializer).serialize(value, jsonGenerator, serializerProvider);
                    } else {
                        jsonGenerator.writePOJOProperty(propertyDefinition.getName(), value);
                    }
                }
            } catch (Exception exception) {
                String message = String.format("Failed to get '%s' field value.", propertyDefinition.getName());
                throw DatabindException.from(serializerProvider, message, exception);
            }
        }
    }
}

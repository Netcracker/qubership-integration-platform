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

package org.qubership.integration.platform.runtime.catalog.service.difference;

import com.google.common.collect.MapDifference;
import com.google.common.collect.Maps;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.hibernate.proxy.HibernateProxy;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Finds the differences between two entities, using fields marked with {@link DifferenceMember}.
 *
 * @param <T> - entity type
 *
 * @since 2024.3
 */
public abstract class EntityDifferenceService<T> {

    private final List<Field> diffMemberFields;

    protected EntityDifferenceService(Class<T> type) {
        this.diffMemberFields = FieldUtils.getFieldsListWithAnnotation(type, DifferenceMember.class);
    }

    public DifferenceResult<T> findDifferences(@Nullable T leftOperand, @Nullable T rightOperand) {
        Map<String, Object> leftOperandMap = leftOperand != null ? convertObjectToMap(leftOperand) : Collections.emptyMap();
        Map<String, Object> rightOperandMap = rightOperand != null ? convertObjectToMap(rightOperand) : Collections.emptyMap();
        MapDifference<String, Object> difference = Maps.difference(leftOperandMap, rightOperandMap);
        return new DifferenceResult<>(
                leftOperand,
                rightOperand,
                difference.entriesOnlyOnLeft().keySet(),
                difference.entriesOnlyOnRight().keySet(),
                difference.entriesDiffering().keySet()
        );
    }

    private Map<String, Object> convertObjectToMap(Object object) {
        Object finalObject;
        if (object instanceof HibernateProxy proxyObject) {
            finalObject = proxyObject.getHibernateLazyInitializer().getImplementation();
        } else {
            finalObject = object;
        }
        return diffMemberFields.stream()
                .flatMap(field -> {
                    try {
                        field.setAccessible(true);
                        Object fieldValue = field.get(finalObject);
                        Map<String, Object> resultMap = new HashMap<>();
                        if (fieldValue instanceof Map<?, ?> fieldValueMap) {
                            for (Object key : fieldValueMap.keySet()) {
                                Object value = fieldValueMap.get(key);
                                if (value != null) {
                                    resultMap.put(field.getName() + "." + key, value);
                                }
                            }
                        } else {
                            resultMap.put(field.getName(), fieldValue);
                        }
                        return resultMap.entrySet().stream();
                    } catch (IllegalAccessException e) {
                        throw new RuntimeException(e);
                    }
                })
                .filter(entry -> entry.getValue() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}

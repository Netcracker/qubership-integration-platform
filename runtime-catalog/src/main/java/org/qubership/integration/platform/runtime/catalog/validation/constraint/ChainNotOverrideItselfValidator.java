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

package org.qubership.integration.platform.runtime.catalog.validation.constraint;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.util.Objects;

@Slf4j
public class ChainNotOverrideItselfValidator implements ConstraintValidator<ChainNotOverrideItself, Object> {

    private static final String ID_FIELD = "id";
    private static final String OVERRIDDEN_BY_FIELD = "overriddenBy";

    @Override
    public boolean isValid(Object object, ConstraintValidatorContext constraintValidatorContext) {
        if (object == null) {
            return true;
        }

        Object id = getFieldValue(object, ID_FIELD);
        Object overriddenBy = getFieldValue(object, OVERRIDDEN_BY_FIELD);

        if (Objects.equals(id, overriddenBy)) {
            constraintValidatorContext.disableDefaultConstraintViolation();
            constraintValidatorContext
                    .buildConstraintViolationWithTemplate("Chain " + id + " cannot be overridden by the same chain")
                    .addConstraintViolation();
            return false;
        }
        return true;
    }

    private Object getFieldValue(Object object, String fieldName) {
        try {
            Field field = object.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(object);
        } catch (NoSuchFieldException e) {
            log.warn("Validation is not configured correctly. Annotated class {} does not have field {}",
                    object.getClass(), fieldName, e);
            return null;
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
}

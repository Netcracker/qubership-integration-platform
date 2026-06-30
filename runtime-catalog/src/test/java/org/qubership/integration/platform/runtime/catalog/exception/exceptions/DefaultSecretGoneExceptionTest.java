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

package org.qubership.integration.platform.runtime.catalog.exception.exceptions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;

class DefaultSecretGoneExceptionTest {

    @Test
    @DisplayName("disabled should create exception with disabled message")
    void disabledShouldCreateExceptionWithDisabledMessage() {
        DefaultSecretGoneException exception = DefaultSecretGoneException.disabled();

        assertThat(exception, instanceOf(RuntimeException.class));
        assertThat(exception.getMessage(), equalTo("Default secret functionality is disabled"));
    }

    @Test
    @DisplayName("addNotAllowed should create exception with add-not-allowed message")
    void addNotAllowedShouldCreateExceptionWithAddNotAllowedMessage() {
        DefaultSecretGoneException exception = DefaultSecretGoneException.addNotAllowed();

        assertThat(exception, instanceOf(RuntimeException.class));
        assertThat(exception.getMessage(), equalTo("Adding new variables to default secret is not allowed"));
    }

    @Test
    @DisplayName("constructor should use provided message")
    void constructorShouldUseProvidedMessage() {
        DefaultSecretGoneException exception = new DefaultSecretGoneException("custom message");

        assertThat(exception.getMessage(), equalTo("custom message"));
    }
}

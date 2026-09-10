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

package org.qubership.integration.platform.runtime.catalog.exception.exceptions.kubernetes;

/**
 * A write lost an optimistic-concurrency race: the object changed between the read that produced
 * the {@code resourceVersion} we sent and this write, or a create found the object already there.
 * Distinct from {@link KubeApiException} so a caller can rebuild and retry rather than treating it
 * as a terminal failure.
 */
public class KubeApiConflictException extends KubeApiException {
    public KubeApiConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}

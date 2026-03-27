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

package org.qubership.integration.platform.runtime.catalog.model.library;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.function.Predicate;

@Schema(description = "Quantity of elements for descriptor")
public enum Quantity implements Predicate<Integer> {

    @JsonProperty("any") ANY {
        public boolean test(Integer count) {
            return true;
        }
    },
    @JsonProperty("one-or-zero") ONE_OR_ZERO {
        public boolean test(Integer count) {
            return count == 0 || count == 1;
        }
    },
    @JsonProperty("one-or-many") ONE_OR_MANY {
        public boolean test(Integer count) {
            return count > 0;
        }
    },
    @JsonProperty("two-or-many") TWO_OR_MANY {
        @Override
        public boolean test(Integer count) {
            return count > 1;
        }
    },
    @JsonProperty("one") ONE {
        @Override
        public boolean test(Integer count) {
            return count == 1;
        }
    }
}

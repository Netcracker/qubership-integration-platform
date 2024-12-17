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

package org.qubership.integration.platform.engine.model.opensearch;

public enum OpenSearchFieldType {
    Text, //
    Keyword, //
    Long, //
    Integer, //
    Short, //
    Byte, //
    Double, //
    Float, //
    Half_Float, //
    Scaled_Float, //
    Date, //
    Date_Nanos, //
    Boolean, //
    Binary, //
    Integer_Range, //
    Float_Range, //
    Long_Range, //
    Double_Range, //
    Date_Range, //
    Ip_Range, //
    Object, //
    Nested, //
    Ip, //
    TokenCount, //
    Percolator, //
    Flattened, //
    Search_As_You_Type, //
    /** @since 4.1 */
    Rank_Feature, //
    /** @since 4.1 */
    Rank_Features, //
    /** since 4.2 */
    Wildcard, //
    /** @since 4.2 */
    Dense_Vector //
}

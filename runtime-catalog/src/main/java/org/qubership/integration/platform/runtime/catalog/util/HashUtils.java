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

package org.qubership.integration.platform.runtime.catalog.util;

import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.lang.Nullable;

public class HashUtils {

    @Nullable
    public static String sha256hex(@Nullable String context) {
        return context == null ? null : DigestUtils.sha256Hex(context);
    }

    @Nullable
    public static String sha1hex(@Nullable String context) {
        return context == null ? null : DigestUtils.sha1Hex(context);
    }
}

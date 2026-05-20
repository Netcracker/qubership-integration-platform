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

package org.qubership.integration.platform.engine.errorhandling.errorcode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ErrorCodeUtils {
    private static final String PATTERN = "\\$\\{(.*?)}";
    private static final int PREFIX_LENGTH = 2;
    private static final int SUFFIX_LENGTH = 1;


    public static List<String> parseExtraKeys(String message) {
        List<String> extraKeys = new ArrayList<>();
        Matcher matcher = Pattern.compile(PATTERN).matcher(message);
        while (matcher.find()) {
            String key = matcher.group();
            extraKeys.add(key.substring(PREFIX_LENGTH, key.length() - SUFFIX_LENGTH));
        }
        return extraKeys;
    }
}

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

package org.qubership.integration.platform.runtime.catalog.util.paths;

import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class PathParser {
    private static final String SEPARATOR = "/";
    private static final char PLACEHOLDER_START = '{';
    private static final char PLACEHOLDER_END = '}';

    public List<PathElement> parse(String pathString) {
        String strippedPath = StringUtils.strip(pathString, SEPARATOR);
        return strippedPath.isEmpty()
                ? Collections.emptyList()
                : Arrays.stream(strippedPath.split(SEPARATOR))
                        .map(this::parseElement).collect(Collectors.toList());
    }

    public PathElement parseElement(String elementString) {
        StringBuilder patternBuilder = new StringBuilder();
        boolean inPlaceholder = false;
        for (int i = 0; i < elementString.length(); ++i) {
            char c = elementString.charAt(i);
            if (c == PLACEHOLDER_START) {
                inPlaceholder = true;
                patternBuilder.append(PathPatternCharacters.PLACEHOLDER);
            } else if (c == PLACEHOLDER_END) {
                inPlaceholder = false;
            } else if (!inPlaceholder) {
                patternBuilder.append(c);
            }
        }
        return new PathElement(patternBuilder.toString());
    }
}

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

package org.qubership.integration.platform.engine.testutils;

import org.junit.jupiter.api.DisplayNameGenerator;

import java.lang.reflect.Method;
import java.util.List;

//TODO Mb we can use it as naming convention validation too
public class DisplayNameUtils {


    public static class ReplaceCamelCase extends DisplayNameGenerator.Standard {
        @Override
        public String generateDisplayNameForClass(Class<?> testClass) {
            return replaceCamelCase(super.generateDisplayNameForClass(testClass));
        }

        @Override
        public String generateDisplayNameForNestedClass(List<Class<?>> enclosingInstanceTypes, Class<?> nestedClass) {
            return replaceCamelCase(super.generateDisplayNameForNestedClass(enclosingInstanceTypes, nestedClass));
        }

        @Override
        public String generateDisplayNameForMethod(List<Class<?>> enclosingInstanceTypes, Class<?> testClass,
                                                   Method testMethod) {
            return this.replaceCamelCase(testMethod.getName())
                    // TODO Are we need () at the end of displayed method name+ DisplayNameGenerator.parameterTypesAsString(testMethod)
                    ;
        }

        //TODO Deal with abbreviation like MDC
        String replaceCamelCase(String camelCase) {
            StringBuilder result = new StringBuilder();
            result.append(camelCase.charAt(0));
            for (int i = 1; i < camelCase.length(); i++) {
                if (Character.isUpperCase(camelCase.charAt(i))) {
                    //TODO do we need capital letters ???
                    result.append(' ');
                    result.append(Character.toLowerCase(camelCase.charAt(i)));
                } else {
                    result.append(camelCase.charAt(i));
                }
            }
            return result.toString();
        }
    }
}

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

package org.qubership.integration.platform.io.readers.chain;

import java.io.IOException;

/**
 * Supplies the content of an element property file referenced from a chain import.
 *
 * <p>Abstracts the chain directory so the mapper can restore properties from real files or, in
 * tests, from an in-memory map without touching the filesystem.
 */
@FunctionalInterface
public interface PropertyFileSource {

    /**
     * Returns the content of the named property file, or {@code null} when the source has no such
     * file.
     *
     * @param fileName name of the property file, as recorded in the element properties
     * @throws IOException if the file exists but cannot be read
     */
    String getFileContent(String fileName) throws IOException;
}

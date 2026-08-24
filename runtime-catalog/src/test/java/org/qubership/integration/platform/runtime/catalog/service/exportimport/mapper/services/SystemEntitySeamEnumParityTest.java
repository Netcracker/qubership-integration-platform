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

package org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the enum name parity {@link SystemEntitySeam} relies on.
 *
 * <p>The seam maps each library/catalog enum pair with {@code valueOf(x.name())}, so the two sides
 * must expose the same set of constant names. Adding a constant to only one side breaks that mapping
 * at runtime; these assertions catch the drift at build time. Only the names are compared: the
 * catalog {@code OperationProtocol} constants carry constructor arguments, while the library copy is
 * bare.
 */
class SystemEntitySeamEnumParityTest {

    @Test
    void integrationSystemTypeNamesMatchAcrossSeam() {
        assertNamesMatch(
                org.qubership.integration.platform.io.model.exportimport.system.IntegrationSystemType.class,
                org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType.class);
    }

    @Test
    void operationProtocolNamesMatchAcrossSeam() {
        assertNamesMatch(
                org.qubership.integration.platform.io.model.exportimport.system.OperationProtocol.class,
                org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol.class);
    }

    /**
     * The one enum the seam does not map by name. {@code CUSTOMER_MANUAL} was retired from the catalog but
     * archives written before this release still state it, so the library keeps accepting it and
     * {@link SystemEntitySeam#toPersistenceSource} folds it into {@code MANUAL}. What has to hold is that every
     * catalog constant still exists on the library side, and that the seam answers for every library constant.
     */
    @Test
    void systemModelSourceIsMappedForEveryLibraryConstant() {
        Set<String> library = names(
                org.qubership.integration.platform.io.model.exportimport.system.SystemModelSource.class);
        Set<String> catalog = names(
                org.qubership.integration.platform.runtime.catalog.model.system.SystemModelSource.class);

        assertTrue(library.containsAll(catalog),
                "the library has to accept every source the catalog can store, got " + library + " vs " + catalog);
        for (org.qubership.integration.platform.io.model.exportimport.system.SystemModelSource source
                : org.qubership.integration.platform.io.model.exportimport.system.SystemModelSource.values()) {
            assertNotNull(SystemEntitySeam.toPersistenceSource(source),
                    "the seam leaves " + source + " unmapped, so an archive stating it fails to import");
        }
    }

    private static void assertNamesMatch(Class<? extends Enum<?>> libraryEnum, Class<? extends Enum<?>> catalogEnum) {
        assertEquals(names(libraryEnum), names(catalogEnum),
                "Constant names of " + libraryEnum.getName() + " and " + catalogEnum.getName()
                        + " must stay identical so the seam can map them by name");
    }

    private static Set<String> names(Class<? extends Enum<?>> enumType) {
        return Arrays.stream(enumType.getEnumConstants()).map(Enum::name).collect(Collectors.toSet());
    }
}

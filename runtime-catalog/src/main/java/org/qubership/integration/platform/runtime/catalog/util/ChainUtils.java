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

import org.apache.commons.lang3.SerializationException;
import org.apache.commons.lang3.SerializationUtils;
import org.hibernate.Hibernate;
import org.hibernate.HibernateException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.CatalogRuntimeException;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;

public class ChainUtils {

    private static final String CHAIN_PROPERTIES_INITIALIZATION_ERROR_MESSAGE = "Unable to initialize properties for chain with id  ";
    private static final String CHAIN_SERIALIZATION_ERROR_MESSAGE = "Unable to create deep copy for chain with id  ";
    private static final String HASH_ALGORITHM = "SHA-512";
    private static final int BUFFER_SIZE = 8192;

    /**
     * Deep clone a Chain using serialization.
     *
     * @param chain - chain instance
     * @return deep copy of chain instance
     * @throws CatalogRuntimeException if chain properties can not been initialized
     *     or chain instance can not been serialized
     */
    public static Chain getChainCopy(Chain chain) {
        if (chain != null) {
            try {
                chainPropertiesInitialization(chain);
                return SerializationUtils.clone(chain);
            } catch (HibernateException e) {
                throw new CatalogRuntimeException(CHAIN_PROPERTIES_INITIALIZATION_ERROR_MESSAGE + chain.getId(), e);
            } catch (SerializationException e) {
                throw new CatalogRuntimeException(CHAIN_SERIALIZATION_ERROR_MESSAGE + chain.getId(), e);
            }
        }
        return null;
    }

    /**
     * Force initialization of Chain persistence object with all nested properties
     */
    public static void chainPropertiesInitialization(Chain chain) throws HibernateException {
        Hibernate.initialize(chain.getLabels());
        Hibernate.initialize(chain.getParentFolder());
        Hibernate.initialize(chain.getElements());
        Hibernate.initialize(chain.getSnapshots());
        Hibernate.initialize(chain.getDeployments());
        Hibernate.initialize(chain.getMaskedFields());
        Hibernate.initialize(chain.getDependencies());
    }

    /**
     * Generate hash value of provided chain configuration files to support uniqueness of chain instance
     * and reduce same chain states overwrites during import
     *
     * @param filesDir chain configuration files from import
     * @param currentArtifactDescriptorVersion current cip build versions. Uses as salt for hash
     * @return Hexadecimal string representation of the hash digest of the given bytes.
     */
    public static String getChainFilesHash(File filesDir, String currentArtifactDescriptorVersion) throws NoSuchAlgorithmException, IOException {
        MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
        File[] files = filesDir.listFiles();

        if (files == null || files.length == 0) {
            return "0";
        }

        digest.update(currentArtifactDescriptorVersion.getBytes(StandardCharsets.UTF_8));

        Arrays.sort(files, Comparator.comparing(File::getName));

        for (File file : files) {
            if (file.isFile()) {
                try (FileInputStream fis = new FileInputStream(file)) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int bytesRead;
                    while ((bytesRead = fis.read(buffer)) != -1) {
                        digest.update(buffer, 0, bytesRead);
                    }
                }
            }
        }

        return convertToHexString(digest.digest());
    }

    private static String convertToHexString(byte[] hashBytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : hashBytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}

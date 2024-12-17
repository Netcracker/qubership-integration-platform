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

package org.qubership.integration.platform.engine.configuration;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Configuration
public class TruststoreConfiguration {
    public final String storeFilePath;
    public final String storePassword;
    public final String certsLocation;

    private final String JAVA_HOME_PROPERTY = "java.home";

    private final String JAVA_TRUSTSTORE_PROPERTY = "javax.net.ssl.trustStore";
    private final String JAVA_TRUSTSTORE_PASSWORD_PROPERTY = "javax.net.ssl.trustStorePassword";

    private final String JAVA_DEFAULT_TRUSTSTORE_JSSE = "/lib/security/jssecacerts";
    private final String JAVA_DEFAULT_TRUSTSTORE = "/lib/security/cacerts";
    private final String JAVA_DEFAULT_TRUSTSTORE_PASSWORD = "changeit";

    @Autowired
    public TruststoreConfiguration(@Value("${qip.local-truststore.store.path}") String storeFilePath,
                                   @Value("${qip.local-truststore.store.password}") String storePassword,
                                   @Value("${qip.local-truststore.certs.location}") String certsLocation) {
        this.storeFilePath = storeFilePath;
        this.storePassword = storePassword;
        this.certsLocation = certsLocation;
    }

    @PostConstruct
    public void buildTruststore() {
        try {
            KeyStore keyStore = getDefaultTrustStore();

            if (Files.exists(Paths.get(certsLocation))) {
                CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
                try (Stream<Path> pathsStream = Files.walk(Paths.get(certsLocation))) {
                    List<Path> paths = pathsStream
                            .filter(Files::isRegularFile)
                            .filter(path -> {
                                String str = path.toString();
                                return str.endsWith(".cer") || str.endsWith(".pem") || str.endsWith(".crt") || str.endsWith(".key");
                            })
                            .collect(Collectors.toList());
                    log.info("Found {} trusted certificates (.cer|.pem|.crt|.key)", paths.size());

                    for (Path certPath : paths) {
                        try {
                            File certFile = new File(certPath.toString());
                            try (BufferedInputStream inputStream = new BufferedInputStream(new FileInputStream(certFile))) {
                                // load and add certs to store
                                Certificate certificate = certificateFactory.generateCertificate(inputStream);
                                keyStore.setCertificateEntry(certFile.getName(), certificate);
                            }
                        } catch (Exception e) {
                            log.error("Failed to load trusted certificate: {}", certPath.toString(), e);
                        }
                    }
                }
            } else {
                log.warn("SSL certificates folder {} not exists", certsLocation);
            }

            // save store to file
            File storeFile = new File(storeFilePath);
            if (storeFile.getParentFile() != null) {
                storeFile.getParentFile().mkdirs();
            }
            try (FileOutputStream fos = new FileOutputStream(storeFile, false)) {
                keyStore.store(fos, storePassword.toCharArray());
            }
        } catch (Exception e) {
            log.error("Failed to load trusted certificates from volume", e);
        }
    }

    /**
    * Get default java Truststore
    * according to the https://docs.oracle.com/javase/8/docs/technotes/guides/security/jsse/JSSERefGuide.html "Creating an X509TrustManager"
    **/
    private KeyStore getDefaultTrustStore() throws GeneralSecurityException, IOException {
        String[] defaultTrustStores = {JAVA_DEFAULT_TRUSTSTORE_JSSE, JAVA_DEFAULT_TRUSTSTORE};
        String trustStorePath = System.getProperty(JAVA_TRUSTSTORE_PROPERTY);
        String trustStorePassword = System.getProperty(JAVA_TRUSTSTORE_PASSWORD_PROPERTY);

        if (StringUtils.isBlank(trustStorePath)) {
            String javaHomePath = System.getProperty(JAVA_HOME_PROPERTY);
            for (String storePath : defaultTrustStores) {
                String fullStorePath = javaHomePath + storePath;
                if ((new File(fullStorePath)).isFile()) {
                    trustStorePath = fullStorePath;
                    trustStorePassword = JAVA_DEFAULT_TRUSTSTORE_PASSWORD;
                    break;
                }
            }
        }
        if (trustStorePassword == null) {
            trustStorePassword = "";
        }

        File trustStoreFile = StringUtils.isBlank(trustStorePath) ? null : new File(trustStorePath);
        try(InputStream is = trustStoreFile == null || !trustStoreFile.isFile() ? null : new FileInputStream(trustStoreFile)) {
            KeyStore keyStore = KeyStore.getInstance("JKS");
            keyStore.load(is, trustStorePassword.toCharArray());
            return keyStore;
        }
    }
}

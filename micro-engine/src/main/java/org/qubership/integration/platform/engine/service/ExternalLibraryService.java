package org.qubership.integration.platform.engine.service;

import java.util.Collection;

public interface ExternalLibraryService {
    void addLibrary(String specificationId, byte[] data);

    ClassLoader getClassLoaderForSpecifications(Collection<String> specificationIds, ClassLoader parentClassLoader);

    ClassLoader getShellClassLoader();
}

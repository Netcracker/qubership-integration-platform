package org.qubership.integration.platform.engine.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class DefaultExternalLibraryServiceTest {

    private DefaultExternalLibraryService service;

    @TempDir
    Path librariesDir;

    @BeforeEach
    void setUp() {
        service = new DefaultExternalLibraryService();
        service.librariesPath = librariesDir.toString();
    }

    @Test
    void shouldSaveLibraryBytesWhenAddingLibrary() throws Exception {
        byte[] data = {1, 2, 3, 4};

        service.addLibrary("lib-a", data);

        assertArrayEquals(data, Files.readAllBytes(librariesDir.resolve("lib-a.jar")));
    }

    @Test
    void shouldReturnParentClassLoaderWhenNoSpecificationMatches() {
        ClassLoader parentClassLoader = getClass().getClassLoader();
        service.addLibrary("lib-a", new byte[]{1});

        ClassLoader result = service.getClassLoaderForSpecifications(List.of("lib-b"), parentClassLoader);

        assertSame(parentClassLoader, result);
    }

    @Test
    void shouldReturnUrlClassLoaderForMatchingSpecifications() throws Exception {
        ClassLoader parentClassLoader = getClass().getClassLoader();
        service.addLibrary("lib-a", new byte[]{1});
        service.addLibrary("lib-b", new byte[]{2});

        ClassLoader result = service.getClassLoaderForSpecifications(List.of("lib-b"), parentClassLoader);

        assertInstanceOf(URLClassLoader.class, result);
        URLClassLoader urlClassLoader = (URLClassLoader) result;
        assertSame(parentClassLoader, urlClassLoader.getParent());
        assertArrayEquals(new URL[]{librariesDir.resolve("lib-b.jar").toUri().toURL()}, urlClassLoader.getURLs());
    }
}

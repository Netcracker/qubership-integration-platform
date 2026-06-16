package org.qubership.integration.platform.engine.camel;

import org.apache.camel.impl.engine.DefaultClassResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class QipCustomClassResolverTest {

    private static final String CLASS_NAME = "custom.TestClass";
    private static final String TYPED_CLASS_NAME = "custom.TypedTestClass";
    private static final String MISSING_CLASS_NAME = "custom.MissingClass";

    private ClassLoader customClassLoader;
    private ClassLoader applicationContextClassLoader;
    private ClassLoader providedClassLoader;
    private ClassLoader defaultClassResolverClassLoader;
    private TestableQipCustomClassResolver resolver;

    @BeforeEach
    void setUp() {
        customClassLoader = isolatedClassLoader();
        applicationContextClassLoader = isolatedClassLoader();
        providedClassLoader = isolatedClassLoader();
        defaultClassResolverClassLoader = DefaultClassResolver.class.getClassLoader();

        resolver = new TestableQipCustomClassResolver(customClassLoader);
    }

    @Test
    void shouldResolveClassFromCustomClassLoaderFirst() {
        resolver.register(customClassLoader, CLASS_NAME, CustomResolvedClass.class);
        resolver.register(defaultClassResolverClassLoader, CLASS_NAME, DefaultResolvedClass.class);
        resolver.setApplicationContextClassLoaderForTest(applicationContextClassLoader);
        resolver.register(applicationContextClassLoader, CLASS_NAME, ApplicationContextResolvedClass.class);

        Class<?> result = resolver.resolveClass(CLASS_NAME);

        assertSame(CustomResolvedClass.class, result);
        assertEquals(List.of(
            new LoadClassCall(CLASS_NAME, customClassLoader)
        ), resolver.getLoadClassCalls());
    }

    @Test
    void shouldFallbackToDefaultClassResolverClassLoaderWhenCustomClassLoaderCannotResolveClass() {
        resolver.register(defaultClassResolverClassLoader, CLASS_NAME, DefaultResolvedClass.class);
        resolver.setApplicationContextClassLoaderForTest(applicationContextClassLoader);
        resolver.register(applicationContextClassLoader, CLASS_NAME, ApplicationContextResolvedClass.class);

        Class<?> result = resolver.resolveClass(CLASS_NAME);

        assertSame(DefaultResolvedClass.class, result);
        assertEquals(List.of(
            new LoadClassCall(CLASS_NAME, customClassLoader),
            new LoadClassCall(CLASS_NAME, defaultClassResolverClassLoader)
        ), resolver.getLoadClassCalls());
    }

    @Test
    void shouldFallbackToApplicationContextClassLoaderWhenPreviousClassLoadersCannotResolveClass() {
        resolver.setApplicationContextClassLoaderForTest(applicationContextClassLoader);
        resolver.register(applicationContextClassLoader, CLASS_NAME, ApplicationContextResolvedClass.class);

        Class<?> result = resolver.resolveClass(CLASS_NAME);

        assertSame(ApplicationContextResolvedClass.class, result);
        assertEquals(List.of(
            new LoadClassCall(CLASS_NAME, customClassLoader),
            new LoadClassCall(CLASS_NAME, defaultClassResolverClassLoader),
            new LoadClassCall(CLASS_NAME, applicationContextClassLoader)
        ), resolver.getLoadClassCalls());
    }

    @Test
    void shouldReturnNullWhenClassCannotBeResolvedAndApplicationContextClassLoaderIsMissing() {
        Class<?> result = resolver.resolveClass(MISSING_CLASS_NAME);

        assertNull(result);
        assertEquals(List.of(
            new LoadClassCall(MISSING_CLASS_NAME, customClassLoader),
            new LoadClassCall(MISSING_CLASS_NAME, defaultClassResolverClassLoader)
        ), resolver.getLoadClassCalls());
    }

    @Test
    void shouldResolveTypedClassUsingCustomClassLoaderFallbackChain() {
        resolver.register(customClassLoader, TYPED_CLASS_NAME, String.class);

        Class<String> result = resolver.resolveClass(TYPED_CLASS_NAME, String.class);

        assertSame(String.class, result);
        assertEquals(List.of(
            new LoadClassCall(TYPED_CLASS_NAME, customClassLoader)
        ), resolver.getLoadClassCalls());
    }

    @Test
    void shouldFallbackToApplicationContextClassLoaderForTypedClassWhenPreviousClassLoadersCannotResolveClass() {
        resolver.setApplicationContextClassLoaderForTest(applicationContextClassLoader);
        resolver.register(applicationContextClassLoader, TYPED_CLASS_NAME, String.class);

        Class<String> result = resolver.resolveClass(TYPED_CLASS_NAME, String.class);

        assertSame(String.class, result);
        assertEquals(List.of(
            new LoadClassCall(TYPED_CLASS_NAME, customClassLoader),
            new LoadClassCall(TYPED_CLASS_NAME, defaultClassResolverClassLoader),
            new LoadClassCall(TYPED_CLASS_NAME, applicationContextClassLoader)
        ), resolver.getLoadClassCalls());
    }

    @Test
    void shouldResolveClassOnlyWithProvidedClassLoaderWhenClassLoaderIsPassedExplicitly() {
        resolver.register(customClassLoader, CLASS_NAME, CustomResolvedClass.class);
        resolver.register(providedClassLoader, CLASS_NAME, ProvidedResolvedClass.class);

        Class<?> result = resolver.resolveClass(CLASS_NAME, providedClassLoader);

        assertSame(ProvidedResolvedClass.class, result);
        assertEquals(List.of(
            new LoadClassCall(CLASS_NAME, providedClassLoader)
        ), resolver.getLoadClassCalls());
    }

    @Test
    void shouldResolveTypedClassOnlyWithProvidedClassLoaderWhenClassLoaderIsPassedExplicitly() {
        resolver.register(customClassLoader, TYPED_CLASS_NAME, Integer.class);
        resolver.register(providedClassLoader, TYPED_CLASS_NAME, String.class);

        Class<String> result = resolver.resolveClass(TYPED_CLASS_NAME, String.class, providedClassLoader);

        assertSame(String.class, result);
        assertEquals(List.of(
            new LoadClassCall(TYPED_CLASS_NAME, providedClassLoader)
        ), resolver.getLoadClassCalls());
    }

    @Test
    void shouldResolveAllMandatoryClassOverloadsWhenClassExists() throws Exception {
        resolver.register(customClassLoader, CLASS_NAME, CustomResolvedClass.class);
        resolver.register(customClassLoader, TYPED_CLASS_NAME, String.class);
        resolver.register(providedClassLoader, CLASS_NAME, ProvidedResolvedClass.class);
        resolver.register(providedClassLoader, TYPED_CLASS_NAME, Integer.class);

        assertAll(
            () -> assertSame(CustomResolvedClass.class, resolver.resolveMandatoryClass(CLASS_NAME)),
            () -> assertSame(String.class, resolver.resolveMandatoryClass(TYPED_CLASS_NAME, String.class)),
            () -> assertSame(ProvidedResolvedClass.class, resolver.resolveMandatoryClass(CLASS_NAME, providedClassLoader)),
            () -> assertSame(Integer.class, resolver.resolveMandatoryClass(
                TYPED_CLASS_NAME,
                Integer.class,
                providedClassLoader
            ))
        );
    }

    @Test
    void shouldThrowClassNotFoundExceptionForAllMandatoryClassOverloadsWhenClassIsMissing() {
        assertAll(
            () -> assertClassNotFound(() -> resolver.resolveMandatoryClass(MISSING_CLASS_NAME)),
            () -> assertClassNotFound(() -> resolver.resolveMandatoryClass(MISSING_CLASS_NAME, String.class)),
            () -> assertClassNotFound(() -> resolver.resolveMandatoryClass(MISSING_CLASS_NAME, providedClassLoader)),
            () -> assertClassNotFound(() -> resolver.resolveMandatoryClass(
                MISSING_CLASS_NAME,
                String.class,
                providedClassLoader
            ))
        );
    }

    private static void assertClassNotFound(ClassResolvingAction action) {
        ClassNotFoundException exception = assertThrows(ClassNotFoundException.class, action::resolve);

        assertEquals(MISSING_CLASS_NAME, exception.getMessage());
    }

    private static ClassLoader isolatedClassLoader() {
        return new ClassLoader(null) {
        };
    }

    private static class TestableQipCustomClassResolver extends QipCustomClassResolver {

        private final Map<ClassLoader, Map<String, Class<?>>> classesByClassLoader = new HashMap<>();
        private final List<LoadClassCall> loadClassCalls = new ArrayList<>();
        private ClassLoader applicationContextClassLoader;

        private TestableQipCustomClassResolver(ClassLoader classLoader) {
            super(classLoader);
        }

        private void register(ClassLoader classLoader, String name, Class<?> clazz) {
            classesByClassLoader
                .computeIfAbsent(classLoader, ignored -> new HashMap<>())
                .put(name, clazz);
        }

        private void setApplicationContextClassLoaderForTest(ClassLoader applicationContextClassLoader) {
            this.applicationContextClassLoader = applicationContextClassLoader;
        }

        private List<LoadClassCall> getLoadClassCalls() {
            return loadClassCalls;
        }

        @Override
        protected ClassLoader getApplicationContextClassLoader() {
            return applicationContextClassLoader;
        }

        @Override
        protected Class<?> loadClass(String name, ClassLoader loader) {
            loadClassCalls.add(new LoadClassCall(name, loader));

            return classesByClassLoader
                .getOrDefault(loader, Map.of())
                .get(name);
        }
    }

    private record LoadClassCall(String name, ClassLoader classLoader) {
    }

    @FunctionalInterface
    private interface ClassResolvingAction {
        void resolve() throws ClassNotFoundException;
    }

    private static class CustomResolvedClass {
    }

    private static class DefaultResolvedClass {
    }

    private static class ApplicationContextResolvedClass {
    }

    private static class ProvidedResolvedClass {
    }
}

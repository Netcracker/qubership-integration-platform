package org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.chain.ChainImportFileMigration;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.revert.RevertMigration;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.revert.TestRevertMigrations;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.system.ServiceImportFileMigration;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.system.TestServiceMigrations;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code FileMigrationService} and {@code ServiceDeserializer} receive their migration lists by injection, and every
 * other test in this module builds those lists by hand. So a migration that loses its {@code @Component} keeps every
 * test green while never running in production. This is the only gate on that.
 */
class MigrationBeanRegistrationTest {

    private static final String ROOT_PACKAGE = "org.qubership.integration.platform.runtime.catalog";

    private static final String SKIPPED_ON_ROLLOUT_HINT =
            "These versions are claimed as applied on the rollout import path, so their migrations never run there. "
                    + "Override isIdempotent() to true if the new migration is safe to re-run, or add its version "
                    + "here to accept that a rollout package skips it.";

    @Test
    void everyRevertMigrationIsAComponentWithItsOwnVersion() {
        assertRegisteredWithUniqueVersions(RevertMigration.class);
    }

    @Test
    void everyServiceImportFileMigrationIsAComponentWithItsOwnVersion() {
        assertRegisteredWithUniqueVersions(ServiceImportFileMigration.class);
    }

    /**
     * A rollout package carries no version data, so both converters stamp {@code MigrationUtil.formatAppliedVersions}
     * — every non-idempotent version, claimed as already applied and therefore never run on that path. A migration
     * added without {@code isIdempotent() == true} inherits that skip in silence, which is how V104 stopped firing
     * on the rollout path once. Pinning the claimed set makes the next one a decision somebody has to take.
     */
    @Test
    void theRolloutImportPathClaimsOnlyTheseServiceMigrationVersions() {
        assertEquals(Set.of(100, 101, 102, 103),
                versionsClaimedByRolloutImport(ServiceImportFileMigration.class),
                SKIPPED_ON_ROLLOUT_HINT);
    }

    @Test
    void theRolloutImportPathClaimsOnlyTheseChainMigrationVersions() {
        assertEquals(Set.of(100, 101, 102, 103, 104, 105, 106, 107, 108),
                versionsClaimedByRolloutImport(ChainImportFileMigration.class),
                SKIPPED_ON_ROLLOUT_HINT);
    }

    /**
     * The two hand-maintained test registries are what every other test builds its migration chain from, and a
     * missing entry leaves them all running an incomplete chain while staying green. This is the only gate on that.
     */
    @Test
    void theServiceMigrationTestRegistryHoldsEveryRegisteredMigration() {
        assertEquals(registeredVersions(ServiceImportFileMigration.class),
                versionsOf(TestServiceMigrations.all(), ImportFileMigration::getVersion),
                "TestServiceMigrations.all() and the registered @Component migrations have drifted apart");
    }

    @Test
    void theRevertMigrationTestRegistryHoldsEveryRegisteredMigration() {
        List<RevertMigration> registry = TestRevertMigrations.all(URI.create("http://example.org/api.schema.yaml"));

        assertEquals(registeredVersions(RevertMigration.class),
                versionsOf(registry, RevertMigration::getVersion),
                "TestRevertMigrations.all() and the registered @Component migrations have drifted apart");
    }

    private static Set<Integer> registeredVersions(Class<?> migrationType) {
        Set<Integer> versions = new HashSet<>();
        for (Class<?> implementation : findImplementations(migrationType)) {
            // Only the injected beans reach production; the scan also sees test doubles on the classpath.
            if (implementation.isAnnotationPresent(Component.class)) {
                versions.add(versionOf(implementation));
            }
        }
        return versions;
    }

    private static <T> Set<Integer> versionsOf(List<T> migrations, ToIntFunction<T> version) {
        return migrations.stream().map(version::applyAsInt).collect(Collectors.toSet());
    }

    private static Set<Integer> versionsClaimedByRolloutImport(Class<?> migrationType) {
        Set<Integer> claimed = new HashSet<>();
        for (Class<?> implementation : findImplementations(migrationType)) {
            // Only the injected beans reach the converter; the scan also sees test subclasses on the classpath.
            if (implementation.isAnnotationPresent(Component.class) && !isIdempotent(implementation)) {
                claimed.add(versionOf(implementation));
            }
        }
        return claimed;
    }

    private static boolean isIdempotent(Class<?> implementation) {
        if (!declaresIsIdempotent(implementation)) {
            // ImportFileMigration.isIdempotent() defaults to false.
            return false;
        }
        Object instance = noArgInstance(implementation);
        if (instance == null) {
            throw new IllegalStateException(implementation.getName() + " overrides isIdempotent() but takes"
                    + " collaborators, so this test cannot read its answer. Extend the test to build it.");
        }
        try {
            return (boolean) implementation.getMethod("isIdempotent").invoke(instance);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static boolean declaresIsIdempotent(Class<?> implementation) {
        for (Class<?> type = implementation; type != null && type != Object.class; type = type.getSuperclass()) {
            try {
                type.getDeclaredMethod("isIdempotent");
                return true;
            } catch (NoSuchMethodException exception) {
                continue;
            }
        }
        return false;
    }

    private static void assertRegisteredWithUniqueVersions(Class<?> migrationType) {
        List<Class<?>> implementations = findImplementations(migrationType);
        assertFalse(implementations.isEmpty(), () -> "found no implementation of " + migrationType.getSimpleName());

        Set<Integer> versions = new HashSet<>();
        for (Class<?> implementation : implementations) {
            assertTrue(implementation.isAnnotationPresent(Component.class),
                    () -> implementation.getName() + " must be a @Component or Spring never injects it");
            assertTrue(versions.add(versionOf(implementation)),
                    () -> implementation.getName() + " repeats a version already claimed by another "
                            + migrationType.getSimpleName());
        }
    }

    private static List<Class<?>> findImplementations(Class<?> migrationType) {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(migrationType));

        List<Class<?>> implementations = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(ROOT_PACKAGE)) {
            try {
                implementations.add(Class.forName(definition.getBeanClassName()));
            } catch (ClassNotFoundException exception) {
                throw new IllegalStateException(exception);
            }
        }
        return implementations;
    }

    private static int versionOf(Class<?> implementation) {
        Object instance = noArgInstance(implementation);
        if (instance == null) {
            // A migration with collaborators cannot be built here; its version is in the class name by convention.
            return Integer.parseInt(implementation.getSimpleName().replaceAll("^V(\\d+).*+$", "$1"));
        }
        try {
            return (int) implementation.getMethod("getVersion").invoke(instance);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Object noArgInstance(Class<?> implementation) {
        try {
            return implementation.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException exception) {
            return null;
        }
    }
}

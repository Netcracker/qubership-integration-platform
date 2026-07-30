package org.qubership.integration.platform.runtime.catalog.rest.v1;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.rest.v1.controller.ApiGroupController;
import org.qubership.integration.platform.runtime.catalog.rest.v1.controller.ApiGroupImportController;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.ApiGroupCreationRequestDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.ApiGroupDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.ApiGroupLabelDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.ApiGroupRequestDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.chain.ChainsByApiGroup;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.RegexPatternTypeFilter;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * springdoc names a component schema after the simple class name and an operation after the method name, so renaming
 * a v1 DTO or controller method rewrites the published document and breaks every generated client. Each rename is
 * therefore paired with a pin that holds the pre-rename name. Nothing else checks the pins are still there.
 */
class PublishedOpenApiNamesTest {

    private static final String V1_DTO_PACKAGE = "org.qubership.integration.platform.runtime.catalog.rest.v1.dto";
    private static final String V1_CONTROLLER_PACKAGE =
            "org.qubership.integration.platform.runtime.catalog.rest.v1.controller";

    private static final Map<Class<?>, String> PINNED_SCHEMA_NAMES = Map.of(
            ApiGroupDTO.class, "SpecificationGroupDTO",
            ApiGroupRequestDTO.class, "SpecificationGroupRequestDTO",
            ApiGroupLabelDTO.class, "SpecificationGroupLabelDTO",
            ApiGroupCreationRequestDTO.class, "SpecificationGroupCreationRequestDTO",
            ChainsByApiGroup.class, "ChainsBySpecificationGroup");

    private static final Map<String, String> PINNED_OPERATION_IDS = Map.of(
            "getApiGroups", "getSpecificationGroups",
            "deleteApiGroup", "deleteSpecificationGroup",
            "createApiGroup", "createSpecificationGroup",
            "importApiGroup", "importSpecificationGroup");

    @Test
    void everyRenamedDtoPublishesItsPreRenameSchemaName() {
        PINNED_SCHEMA_NAMES.forEach((type, publishedName) -> {
            Schema schema = type.getAnnotation(Schema.class);
            assertNotNull(schema, () -> type.getName() + " must declare @Schema(name = \"" + publishedName + "\")");
            assertEquals(publishedName, schema.name(),
                    () -> type.getName() + " publishes a component schema under a changed name");
        });
    }

    @Test
    void everyRenamedControllerMethodPublishesItsPreRenameOperationId() {
        List<Method> methods = new ArrayList<>();
        methods.addAll(List.of(ApiGroupController.class.getDeclaredMethods()));
        methods.addAll(List.of(ApiGroupImportController.class.getDeclaredMethods()));

        for (Method method : methods) {
            String publishedName = PINNED_OPERATION_IDS.get(method.getName());
            if (publishedName == null) {
                continue;
            }
            Operation operation = method.getAnnotation(Operation.class);
            assertNotNull(operation, () -> method.getName() + " must declare @Operation(operationId = ...)");
            assertEquals(publishedName, operation.operationId(),
                    () -> method.getName() + " publishes an operation under a changed operationId");
        }
        assertEquals(PINNED_OPERATION_IDS.size(),
                methods.stream().filter(method -> PINNED_OPERATION_IDS.containsKey(method.getName())).count(),
                "a pinned method disappeared or was renamed again");
    }

    /**
     * The forward-looking half: any further api-group rename under rest.v1 has to bring its own pin along, whether or
     * not it is listed above.
     */
    @Test
    void everyApiGroupTypeAndMethodUnderV1CarriesAPin() {
        for (Class<?> type : scanClasses(V1_DTO_PACKAGE)) {
            // Lombok's generated builders are member classes and never reach the published document.
            if (!type.getSimpleName().contains("ApiGroup") || type.isInterface() || type.isMemberClass()) {
                continue;
            }
            Schema schema = type.getAnnotation(Schema.class);
            assertTrue(schema != null && !schema.name().isBlank(),
                    () -> type.getName() + " renames a published component schema without pinning its name");
            assertFalse(schema.name().contains("ApiGroup"),
                    () -> type.getName() + " pins a name that itself changed");
        }

        for (Class<?> type : scanClasses(V1_CONTROLLER_PACKAGE)) {
            if (!type.isAnnotationPresent(RestController.class)) {
                continue;
            }
            for (Method method : type.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers()) || !method.getName().contains("ApiGroup")) {
                    continue;
                }
                Operation operation = method.getAnnotation(Operation.class);
                assertTrue(operation != null && !operation.operationId().isBlank(),
                        () -> type.getName() + "#" + method.getName()
                                + " renames a published operation without pinning its operationId");
            }
        }
    }

    private static List<Class<?>> scanClasses(String packageName) {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile(".*")));

        List<Class<?>> classes = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(packageName)) {
            try {
                classes.add(Class.forName(definition.getBeanClassName()));
            } catch (ClassNotFoundException exception) {
                throw new IllegalStateException(exception);
            }
        }
        assertFalse(classes.isEmpty(), () -> "the scan of " + packageName + " returned nothing");
        return classes;
    }
}

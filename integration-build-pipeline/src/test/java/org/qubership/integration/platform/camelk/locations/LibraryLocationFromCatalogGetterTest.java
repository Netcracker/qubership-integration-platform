package org.qubership.integration.platform.camelk.locations;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.camelk.model.BuildInfo;
import org.qubership.integration.platform.camelk.model.ResourceBuildContext;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LibraryLocationFromCatalogGetterTest {

    @Test
    void encodesSpecificationIdWithSpacesAndOtherReservedCharacters() {
        LibraryLocationFromCatalogGetter getter = new LibraryLocationFromCatalogGetter();
        ReflectionTestUtils.setField(getter, "cloudServiceName", "cloud-integration-platform-catalog-v1");

        ResourceBuildContext<String> context = ResourceBuildContext
                .create(BuildInfo.builder().build())
                .updateTo("quote-tmf-service-Quote Management6.2-6.2");

        String location = getter.apply(context);

        assertEquals(
                "http://cloud-integration-platform-catalog-v1:8080/v1/models/"
                        + "quote-tmf-service-Quote%20Management6.2-6.2/dto/jar",
                location);
    }

    @Test
    void leavesUrlSafeSpecificationIdUnchanged() {
        LibraryLocationFromCatalogGetter getter = new LibraryLocationFromCatalogGetter();
        ReflectionTestUtils.setField(getter, "cloudServiceName", "cloud-integration-platform-catalog-v1");

        ResourceBuildContext<String> context = ResourceBuildContext
                .create(BuildInfo.builder().build())
                .updateTo("7f969279-ca8f-4c1d-8fc6-2aafbd1dec42");

        String location = getter.apply(context);

        assertEquals(
                "http://cloud-integration-platform-catalog-v1:8080/v1/models/"
                        + "7f969279-ca8f-4c1d-8fc6-2aafbd1dec42/dto/jar",
                location);
    }
}

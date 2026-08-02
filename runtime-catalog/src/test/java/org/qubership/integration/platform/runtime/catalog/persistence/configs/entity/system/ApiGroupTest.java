package org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the API group entity: label projections, the system-model back-reference, and the
 * strict/non-strict equality used by import comparison.
 */
class ApiGroupTest {

    private static ApiGroup group(String id, String name) {
        ApiGroup group = new ApiGroup();
        group.setId(id);
        group.setName(name);
        return group;
    }

    private static ApiGroupLabel label(String name, boolean technical) {
        ApiGroupLabel label = new ApiGroupLabel();
        label.setName(name);
        label.setTechnical(technical);
        return label;
    }

    private static Set<String> names(Set<ApiGroupLabel> labels) {
        return labels.stream().map(ApiGroupLabel::getName).collect(Collectors.toSet());
    }

    @Test
    void shouldExposeOnlyNonTechnicalLabels() {
        ApiGroup group = group("g1", "Petstore");
        group.setLabels(new LinkedHashSet<>(List.of(label("visible", false), label("hidden", true))));

        assertEquals(Set.of("visible"), names(group.getNonTechnicalLabels()));
    }

    @Test
    void shouldReturnLabelsUnchangedWhenNoneArePresent() {
        ApiGroup group = group("g1", "Petstore");

        assertTrue(group.getNonTechnicalLabels().isEmpty());
    }

    @Test
    void shouldBuildLabelsFromNamesAndPointThemBackAtTheGroup() {
        ApiGroup group = group("g1", "Petstore");

        group.setNonTechnicalLabels(new LinkedHashSet<>(List.of("alpha", "beta")));

        assertEquals(Set.of("alpha", "beta"), names(group.getLabels()));
        assertTrue(group.getLabels().stream().allMatch(label -> label.getApiGroup() == group));
    }

    @Test
    void shouldKeepLabelsUntouchedWhenNameSetIsEmpty() {
        ApiGroup group = group("g1", "Petstore");
        group.setLabels(new LinkedHashSet<>(List.of(label("kept", false))));

        group.setNonTechnicalLabels(Set.of());

        assertEquals(Set.of("kept"), names(group.getLabels()));
    }

    @Test
    void shouldReplaceLabelContentOnSet() {
        ApiGroup group = group("g1", "Petstore");
        group.setLabels(new LinkedHashSet<>(List.of(label("old", false))));

        group.setLabels(new LinkedHashSet<>(List.of(label("new", false))));

        assertEquals(Set.of("new"), names(group.getLabels()));
    }

    @Test
    void shouldAddLabelToTheGroup() {
        ApiGroup group = group("g1", "Petstore");

        group.addLabel(label("single", false));

        assertEquals(Set.of("single"), names(group.getLabels()));
    }

    @Test
    void shouldCreateSystemModelListOnFirstAccess() {
        ApiGroup group = group("g1", "Petstore");

        assertNotNull(group.getSystemModels());
        assertTrue(group.getSystemModels().isEmpty());
    }

    @Test
    void shouldLinkSystemModelBackToTheGroupWhenAdded() {
        ApiGroup group = group("g1", "Petstore");
        SystemModel model = new SystemModel();
        model.setId("m1");

        group.addSystemModel(model);

        assertEquals(List.of(model), group.getSystemModels());
        assertSame(group, model.getApiGroup());
    }

    @Test
    void shouldClearTheBackReferenceWhenSystemModelIsRemoved() {
        ApiGroup group = group("g1", "Petstore");
        SystemModel model = new SystemModel();
        model.setId("m1");
        group.addSystemModel(model);

        group.removeSystemModel(model);

        assertTrue(group.getSystemModels().isEmpty());
        assertNull(model.getApiGroup());
    }

    @Test
    void shouldEqualItself() {
        ApiGroup group = group("g1", "Petstore");

        assertTrue(group.equals(group, true));
    }

    @Test
    void shouldNotEqualNull() {
        assertFalse(group("g1", "Petstore").equals(null, true));
    }

    @Test
    void shouldNotEqualAnotherType() {
        assertFalse(group("g1", "Petstore").equals("not a group", true));
    }

    @Test
    void shouldEqualGroupWithSameIdentityAndUrl() {
        ApiGroup left = group("g1", "Petstore");
        left.setUrl("http://example.test");
        ApiGroup right = group("g1", "Petstore");
        right.setUrl("http://example.test");

        assertTrue(left.equals(right, true));
    }

    @Test
    void shouldNotEqualWhenUrlDiffers() {
        ApiGroup left = group("g1", "Petstore");
        left.setUrl("http://example.test");
        ApiGroup right = group("g1", "Petstore");
        right.setUrl("http://other.test");

        assertFalse(left.equals(right, true));
    }

    @Test
    void shouldIgnoreIdWhenComparisonIsNotStrict() {
        ApiGroup left = group("g1", "Petstore");
        ApiGroup right = group("g2", "Petstore");

        assertFalse(left.equals(right, true), "a strict comparison still separates the two ids");
        assertTrue(left.equals(right, false), "a non-strict comparison ignores the id");
    }

    @Test
    void shouldHashEqualGroupsAlike() {
        ApiGroup left = group("g1", "Petstore");
        left.setUrl("http://example.test");
        ApiGroup right = group("g1", "Petstore");
        right.setUrl("http://example.test");

        assertTrue(left.equals(right, true), "precondition: the two are equal");
        assertEquals(left.hashCode(), right.hashCode());
    }

    @Test
    void shouldHashWithoutTouchingSystemModels() {
        // The hash must stay usable on a group whose lazy model list was never loaded.
        ApiGroup group = group("g1", "Petstore");
        group.setUrl("http://example.test");
        int before = group.hashCode();
        group.addSystemModel(new SystemModel());

        assertEquals(before, group.hashCode());
    }

    @Test
    void shouldNotEqualWhenSystemModelsDiffer() {
        ApiGroup left = group("g1", "Petstore");
        SystemModel model = new SystemModel();
        model.setId("m1");
        model.setName("v1");
        left.addSystemModel(model);
        ApiGroup right = group("g1", "Petstore");

        assertFalse(left.equals(right, true));
    }
}

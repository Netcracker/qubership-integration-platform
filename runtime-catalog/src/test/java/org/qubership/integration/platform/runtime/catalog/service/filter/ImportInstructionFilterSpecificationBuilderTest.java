package org.qubership.integration.platform.runtime.catalog.service.filter;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.model.filter.FilterCondition;
import org.qubership.integration.platform.runtime.catalog.model.filter.FilterFeature;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.instructions.ImportInstruction;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.FilterRequestDTO;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.function.BiFunction;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code entityType} is stored as a raw string, so Jackson's {@code @JsonAlias} never reaches it and the builder
 * translates the pre-rename value by hand. IN and NOT_IN split the value on commas downstream, which is why the
 * translation has to run per element rather than on the whole string.
 */
@ExtendWith(MockitoExtension.class)
class ImportInstructionFilterSpecificationBuilderTest {

    @Mock
    private FilterConditionPredicateBuilderFactory predicateBuilderFactory;

    @Mock
    private CriteriaBuilder criteriaBuilder;

    @Mock
    private Root<ImportInstruction> root;

    @Mock
    private CriteriaQuery<?> query;

    @InjectMocks
    private ImportInstructionFilterSpecificationBuilder builder;

    @ParameterizedTest
    @CsvSource({
            "IS,SPECIFICATION_GROUP,API_GROUP",
            "IS_NOT,SPECIFICATION_GROUP,API_GROUP",
            "IN,'SPECIFICATION_GROUP,CHAIN','API_GROUP,CHAIN'",
            "NOT_IN,'CHAIN,SPECIFICATION_GROUP','CHAIN,API_GROUP'",
            "IS,API_GROUP,API_GROUP",
            "IS,CHAIN,CHAIN"
    })
    @DisplayName("the entity-type filter translates every pre-rename element before it reaches the query")
    void normalizesTheEntityTypeFilterValue(FilterCondition condition, String given, String expected) {
        assertEntityTypeFilterBuildsWith(condition, given, expected);
    }

    @Test
    @DisplayName("a filter on another column is passed through untouched")
    void leavesOtherFeaturesAlone() {
        Path<String> path = mockPath();
        BiFunction<Expression<String>, String, Predicate> predicateBuilder = mockPredicateBuilder();
        Predicate predicate = mock(Predicate.class);

        when(predicateBuilderFactory.<String>getPredicateBuilder(criteriaBuilder, FilterCondition.IS))
                .thenReturn(predicateBuilder);
        doReturn(path).when(root).get("id");
        when(predicateBuilder.apply(path, "SPECIFICATION_GROUP")).thenReturn(predicate);

        build(FilterFeature.ID, FilterCondition.IS, "SPECIFICATION_GROUP");

        verify(predicateBuilder).apply(path, "SPECIFICATION_GROUP");
    }

    private void assertEntityTypeFilterBuildsWith(FilterCondition condition, String given, String expected) {
        Path<String> path = mockPath();
        BiFunction<Expression<String>, String, Predicate> predicateBuilder = mockPredicateBuilder();
        Predicate predicate = mock(Predicate.class);

        when(predicateBuilderFactory.<String>getPredicateBuilder(criteriaBuilder, condition))
                .thenReturn(predicateBuilder);
        doReturn(path).when(root).get("entityType");
        when(predicateBuilder.apply(path, expected)).thenReturn(predicate);

        build(FilterFeature.ENTITY_TYPE, condition, given);

        verify(predicateBuilder).apply(path, expected);
    }

    private void build(FilterFeature feature, FilterCondition condition, String value) {
        Specification<ImportInstruction> specification = builder.buildFilter(List.of(FilterRequestDTO.builder()
                .feature(feature)
                .condition(condition)
                .value(value)
                .build()));
        specification.toPredicate(root, query, criteriaBuilder);
    }

    @SuppressWarnings("unchecked")
    private static Path<String> mockPath() {
        return mock(Path.class);
    }

    @SuppressWarnings("unchecked")
    private static BiFunction<Expression<String>, String, Predicate> mockPredicateBuilder() {
        return mock(BiFunction.class);
    }
}

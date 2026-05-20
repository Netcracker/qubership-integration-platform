package org.qubership.integration.platform.runtime.catalog.service.filter;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.SetJoin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.model.filter.FilterCondition;
import org.qubership.integration.platform.runtime.catalog.model.filter.FilterFeature;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.context.ContextSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.context.ContextSystemLabel;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.FilterRequestDTO;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SystemFilterSpecificationBuilderTest {

    @Mock
    private FilterConditionPredicateBuilderFactory predicateBuilderFactory;

    @Mock
    private CriteriaBuilder criteriaBuilder;

    @Mock
    private Root<ContextSystem> root;

    @Mock
    private CriteriaQuery query;

    private SystemFilterSpecificationBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new SystemFilterSpecificationBuilder(predicateBuilderFactory);
    }

    @Nested
    @DisplayName("buildContextPredicate via buildContextFilter - Supported Features")
    class BuildContextPredicateSupportedFeatures {

        @Test
        @DisplayName("Should build predicate for ID feature")
        void shouldBuildPredicateForIdFeature() {
            // Given
            var mockPredicate = mock(Predicate.class);
            var mockPath = mock(Path.class);
            @SuppressWarnings("unchecked")
            BiFunction<Expression<String>, String, Predicate> mockPredicateBuilder = mock(BiFunction.class);

            var filter = FilterRequestDTO.builder()
                    .feature(FilterFeature.ID)
                    .condition(FilterCondition.IS)
                    .value("test-id")
                    .build();

            when(predicateBuilderFactory.<String>getPredicateBuilder(criteriaBuilder, FilterCondition.IS))
                    .thenReturn(mockPredicateBuilder);
            when(root.get("id")).thenReturn(mockPath);
            when(mockPredicateBuilder.apply(mockPath, "test-id")).thenReturn(mockPredicate);

            // When
            Specification<ContextSystem> spec = builder.buildContextFilter(List.of(filter));

            var result = spec.toPredicate(root, query, criteriaBuilder);

            verify(predicateBuilderFactory).getPredicateBuilder(criteriaBuilder, filter.getCondition());
            verify(mockPredicateBuilder).apply(mockPath, "test-id");
            assertThat(result).isSameAs(mockPredicate);
        }

        @Test
        @DisplayName("Should build predicate for NAME feature")
        void shouldBuildPredicateForNameFeature() {
            // Given
            var mockPredicate = mock(Predicate.class);
            var mockPath = mock(Path.class);
            @SuppressWarnings("unchecked")
            BiFunction<Expression<String>, String, Predicate> mockPredicateBuilder = mock(BiFunction.class);

            var filter = FilterRequestDTO.builder()
                    .feature(FilterFeature.NAME)
                    .condition(FilterCondition.CONTAINS)
                    .value("test-name")
                    .build();

            when(predicateBuilderFactory.<String>getPredicateBuilder(criteriaBuilder, FilterCondition.CONTAINS))
                    .thenReturn(mockPredicateBuilder);
            when(root.get("name")).thenReturn(mockPath);
            when(mockPredicateBuilder.apply(mockPath, "test-name")).thenReturn(mockPredicate);

            // When
            var spec = builder.buildContextFilter(List.of(filter));
            var result = spec.toPredicate(root, query, criteriaBuilder);

            // Then
            verify(predicateBuilderFactory).getPredicateBuilder(criteriaBuilder, FilterCondition.CONTAINS);
            verify(root).get("name");
            verify(mockPredicateBuilder).apply(mockPath, "test-name");
            assertThat(result).isSameAs(mockPredicate);
        }

        @Test
        @DisplayName("Should build predicate for CREATED feature")
        void shouldBuildPredicateForCreatedFeature() {
            // Given
            var mockPredicate = mock(Predicate.class);
            var mockPath = mock(Path.class);
            @SuppressWarnings("unchecked")
            BiFunction<Expression<String>, String, Predicate> mockPredicateBuilder = mock(BiFunction.class);

            var filter = FilterRequestDTO.builder()
                    .feature(FilterFeature.CREATED)
                    .condition(FilterCondition.IS_AFTER)
                    .value("1234567890")
                    .build();

            when(predicateBuilderFactory.<String>getPredicateBuilder(criteriaBuilder, FilterCondition.IS_AFTER))
                    .thenReturn(mockPredicateBuilder);
            when(root.get("createdWhen")).thenReturn(mockPath);
            when(mockPredicateBuilder.apply(mockPath, "1234567890")).thenReturn(mockPredicate);

            // When
            var spec = builder.buildContextFilter(List.of(filter));
            var result = spec.toPredicate(root, query, criteriaBuilder);

            // Then
            verify(predicateBuilderFactory).getPredicateBuilder(criteriaBuilder, FilterCondition.IS_AFTER);
            verify(root).get("createdWhen");
            verify(mockPredicateBuilder).apply(mockPath, "1234567890");
            assertThat(result).isSameAs(mockPredicate);
        }
    }

    @Nested
    @DisplayName("buildContextPredicate - LABELS Feature with buildLabelsPredicate")
    class BuildContextPredicateLabelsFeature {

        @Mock
        private SetJoin<ContextSystem, ContextSystemLabel> labelsJoin;

        @Mock
        private Path<String> labelNamePath;

        @Mock
        private Predicate mockPredicate;

        @BeforeEach
        void setUpLabels() {
            @SuppressWarnings("unchecked")
            BiFunction<Expression<String>, String, Predicate> mockPredicateBuilder = mock(BiFunction.class);
            when(predicateBuilderFactory.<String>getPredicateBuilder(any(), any())).thenReturn(mockPredicateBuilder);
            when(root.getJoins()).thenReturn(Set.of(labelsJoin));
            when(labelsJoin.getAttribute()).thenReturn(mock(jakarta.persistence.metamodel.Attribute.class));
            when(labelsJoin.getAttribute().getName()).thenReturn("labels");

            doReturn(labelNamePath).when(labelsJoin).get("name");
            when(mockPredicateBuilder.apply(eq(labelNamePath), anyString())).thenReturn(mockPredicate);
        }

        @Test
        @DisplayName("Should build LABELS predicate with positive condition (IS)")
        void shouldBuildLabelsPredicateWithPositiveCondition() {
            // Given
            var filter = FilterRequestDTO.builder()
                    .feature(FilterFeature.LABELS)
                    .condition(FilterCondition.IS)
                    .value("production")
                    .build();

            // When
            var spec = builder.buildContextFilter(List.of(filter));
            var result = spec.toPredicate(root, query, criteriaBuilder);

            // Then
            verify(root).getJoins();
            verify(labelsJoin).get("name");
            verify(criteriaBuilder, never()).or(any(), any());
            verify(criteriaBuilder, never()).isNull(any());
            assertThat(result).isSameAs(mockPredicate);
        }

        @Test
        @DisplayName("Should build LABELS predicate with IS_NOT condition (negative - adds OR with isNull)")
        void shouldBuildLabelsPredicateWithIsNotCondition() {
            // Given
            var mockIsNullPredicate = mock(Predicate.class);
            var mockOrPredicate = mock(Predicate.class);

            var filter = FilterRequestDTO.builder()
                    .feature(FilterFeature.LABELS)
                    .condition(FilterCondition.IS_NOT)
                    .value("deprecated")
                    .build();

            when(criteriaBuilder.isNull(labelNamePath)).thenReturn(mockIsNullPredicate);
            when(criteriaBuilder.or(mockPredicate, mockIsNullPredicate)).thenReturn(mockOrPredicate);

            // When
            var spec = builder.buildContextFilter(List.of(filter));
            var result = spec.toPredicate(root, query, criteriaBuilder);

            // Then
            verify(criteriaBuilder).isNull(labelNamePath);
            verify(criteriaBuilder).or(mockPredicate, mockIsNullPredicate);
            assertThat(result).isSameAs(mockOrPredicate);
        }

        @Test
        @DisplayName("Should build LABELS predicate with DOES_NOT_CONTAIN condition (negative - adds OR with isNull)")
        void shouldBuildLabelsPredicateWithDoesNotContainCondition() {
            // Given
            var mockIsNullPredicate = mock(Predicate.class);
            var mockOrPredicate = mock(Predicate.class);

            var filter = FilterRequestDTO.builder()
                    .feature(FilterFeature.LABELS)
                    .condition(FilterCondition.DOES_NOT_CONTAIN)
                    .value("test")
                    .build();

            when(criteriaBuilder.isNull(labelNamePath)).thenReturn(mockIsNullPredicate);
            when(criteriaBuilder.or(mockPredicate, mockIsNullPredicate)).thenReturn(mockOrPredicate);

            // When
            var spec = builder.buildContextFilter(List.of(filter));
            var result = spec.toPredicate(root, query, criteriaBuilder);

            // Then
            verify(criteriaBuilder).isNull(labelNamePath);
            verify(criteriaBuilder).or(mockPredicate, mockIsNullPredicate);
            assertThat(result).isSameAs(mockOrPredicate);
        }
    }

    @Nested
    @DisplayName("buildContextPredicate - Edge Cases and Error Handling")
    class BuildContextPredicateEdgeCases {

        @Test
        @DisplayName("Should throw IllegalStateException for unsupported ContextSystem features")
        void shouldThrowExceptionForUnsupportedFeature() {
            // Given - SPECIFICATION_GROUP is valid for IntegrationSystem but NOT for
            // ContextSystem
            var filter = FilterRequestDTO.builder()
                    .feature(FilterFeature.SPECIFICATION_GROUP)
                    .condition(FilterCondition.IS)
                    .value("test")
                    .build();

            // When & Then
            var spec = builder.buildContextFilter(List.of(filter));
            assertThatThrownBy(() -> spec.toPredicate(root, query, criteriaBuilder))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Unexpected feature value: SPECIFICATION_GROUP");
        }
    }
}

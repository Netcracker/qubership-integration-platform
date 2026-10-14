package org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.BadRequestException;
import org.qubership.integration.platform.runtime.catalog.model.filter.ChainElementFilterColumn;
import org.qubership.integration.platform.runtime.catalog.model.filter.FilterCondition;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElementFilterRequestDTO;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The TYPE column reads its values from one comma-separated string. A value the builder does not
 * recognize used to leave the filter out of the query, so the caller got the whole table under a 200.
 */
@ExtendWith(MockitoExtension.class)
class ElementFilterRepositoryImplTest {

    @Mock
    private EntityManager entityManager;
    @Mock
    private CriteriaBuilder criteriaBuilder;
    @Mock
    private CriteriaQuery<ChainElement> criteriaQuery;
    @Mock
    private Root<ChainElement> root;
    @Mock
    private TypedQuery<ChainElement> typedQuery;
    @Mock
    private Path<Object> propertiesPath;
    @Mock
    private Expression<Object> objectExpression;
    @Mock
    private Predicate predicate;

    private ElementFilterRepositoryImpl repository;

    @BeforeEach
    void setUp() {
        repository = new ElementFilterRepositoryImpl();
        ReflectionTestUtils.setField(repository, "entityManager", entityManager);
        when(entityManager.getCriteriaBuilder()).thenReturn(criteriaBuilder);
        when(criteriaBuilder.createQuery(ChainElement.class)).thenReturn(criteriaQuery);
        when(criteriaQuery.from(ChainElement.class)).thenReturn(root);
        lenient().when(root.get(anyString())).thenReturn(propertiesPath);
        lenient().when(criteriaBuilder.isNotNull(any())).thenReturn(predicate);
        lenient().when(propertiesPath.in(any(List.class))).thenReturn(predicate);
        lenient().doReturn(objectExpression).when(criteriaBuilder).function(anyString(), any(), any(), any());
        lenient().doReturn(objectExpression).when(criteriaBuilder).function(anyString(), any(), any());
        lenient().doReturn(predicate).when(criteriaBuilder).equal(nullable(Expression.class), any());
        lenient().doReturn(predicate).when(criteriaBuilder).or(any(Predicate.class), any(Predicate.class));
    }

    @Test
    @DisplayName("A route type the builder does not recognize is rejected, not dropped")
    void unknownRouteTypeIsRejected() {
        assertThatThrownBy(() -> findByType("Foo"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Foo")
                .hasMessageContaining("TYPE");
    }

    @Test
    @DisplayName("The space in the UI's own \"External, Private\" option no longer drops the second type")
    void combinedRouteTypeKeepsBothValues() {
        when(criteriaBuilder.and(any(Predicate[].class))).thenReturn(predicate);
        when(criteriaQuery.select(root)).thenReturn(criteriaQuery);
        when(criteriaQuery.where(predicate)).thenReturn(criteriaQuery);
        when(entityManager.createQuery(criteriaQuery)).thenReturn(typedQuery);
        when(typedQuery.setFirstResult(0)).thenReturn(typedQuery);
        when(typedQuery.setMaxResults(30)).thenReturn(typedQuery);
        when(typedQuery.getResultList()).thenReturn(List.of());

        findByType("External, Private");

        // Without the trim, " Private" would have been rejected instead of reaching this property.
        verify(criteriaBuilder).literal("externalRoute");
        verify(criteriaBuilder).literal("privateRoute");
    }

    private void findByType(String value) {
        ChainElementFilterRequestDTO filter = new ChainElementFilterRequestDTO();
        filter.setColumn(ChainElementFilterColumn.TYPE);
        filter.setCondition(FilterCondition.IN);
        filter.setValue(value);
        repository.findElementsByFilter(0, 30, List.of("http-trigger"),
                new ArrayList<>(List.of(filter)), false);
    }
}

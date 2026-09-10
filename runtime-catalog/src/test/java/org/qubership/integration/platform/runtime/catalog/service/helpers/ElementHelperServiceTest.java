package org.qubership.integration.platform.runtime.catalog.service.helpers;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.library.constants.CamelOptions;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.ElementRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ContextConfiguration(classes = ElementHelperService.class)
@ExtendWith(SpringExtension.class)
@ExtendWith(MockitoExtension.class)
class ElementHelperServiceTest {

    private static final String CHAIN_ATTRIBUTE = "chain";
    private static final String PROPERTIES_ATTRIBUTE = "properties";
    private static final String JSON_EXTRACT_FUNCTION = "jsonb_extract_path_text";

    @MockitoBean
    ElementRepository elementRepository;

    @Autowired
    ElementHelperService elementHelperService;

    private static ChainElement element(String systemId, Chain chain) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(CamelOptions.SYSTEM_ID, systemId);
        ChainElement element = new ChainElement();
        element.setProperties(properties);
        element.setChain(chain);
        return element;
    }

    private static Chain chain(String id) {
        Chain chain = new Chain();
        chain.setId(id);
        return chain;
    }

    @Test
    @DisplayName("Chains are grouped by the service they use, in a single repository call")
    void groupsChainsBySystemId() {
        Chain first = chain("chain-1");
        Chain second = chain("chain-2");
        when(elementRepository.findAll(ArgumentMatchers.<Specification<ChainElement>>any()))
                .thenReturn(List.of(
                        element("system-1", first),
                        element("system-1", second),
                        element("system-2", first)));

        Map<String, List<Chain>> result = elementHelperService.findChainsGroupedBySystemId();

        assertThat(result.keySet(), containsInAnyOrder("system-1", "system-2"));
        assertThat(result.get("system-1"), containsInAnyOrder(first, second));
        assertThat(result.get("system-2"), contains(first));
        verify(elementRepository, times(1)).findAll(ArgumentMatchers.<Specification<ChainElement>>any());
    }

    @Test
    @DisplayName("The same chain is reported once per service when several elements reference it")
    void deduplicatesChainsPerSystem() {
        Chain chain = chain("chain-1");
        when(elementRepository.findAll(ArgumentMatchers.<Specification<ChainElement>>any()))
                .thenReturn(List.of(element("system-1", chain), element("system-1", chain)));

        Map<String, List<Chain>> result = elementHelperService.findChainsGroupedBySystemId();

        assertThat(result.get("system-1"), contains(chain));
    }

    @Test
    @DisplayName("A service used by no chain is absent from the result")
    void returnsNoEntryForUnusedSystem() {
        when(elementRepository.findAll(ArgumentMatchers.<Specification<ChainElement>>any()))
                .thenReturn(List.of());

        assertThat(elementHelperService.findChainsGroupedBySystemId().entrySet(), empty());
    }

    @Test
    @DisplayName("An element carrying no service id is skipped rather than grouped under null")
    void skipsElementWithoutSystemId() {
        Chain chain = chain("chain-1");
        when(elementRepository.findAll(ArgumentMatchers.<Specification<ChainElement>>any()))
                .thenReturn(List.of(element(null, chain), element("system-1", chain)));

        Map<String, List<Chain>> result = elementHelperService.findChainsGroupedBySystemId();

        assertThat(result.keySet(), containsInAnyOrder("system-1"));
    }

    @Test
    @DisplayName("The query asks for chain elements whose service id property is set")
    void queriesElementsWithSystemIdProperty() {
        when(elementRepository.findAll(ArgumentMatchers.<Specification<ChainElement>>any()))
                .thenReturn(List.of());

        elementHelperService.findChainsGroupedBySystemId();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Specification<ChainElement>> captor = ArgumentCaptor.forClass(Specification.class);
        verify(elementRepository).findAll(captor.capture());

        Root<ChainElement> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder builder = mock(CriteriaBuilder.class);
        Path<Object> chainPath = mock(Path.class);
        Path<String> propertiesPath = mock(Path.class);
        Expression<String> extracted = mock(Expression.class);
        Predicate chainNotNull = mock(Predicate.class);
        Predicate systemIdNotNull = mock(Predicate.class);
        Predicate conjunction = mock(Predicate.class);

        when(root.get("chain")).thenReturn(chainPath);
        when(root.<String>get("properties")).thenReturn(propertiesPath);
        when(builder.function(eq("jsonb_extract_path_text"), eq(String.class), any(), any()))
                .thenReturn(extracted);
        when(builder.isNotNull(chainPath)).thenReturn(chainNotNull);
        when(builder.isNotNull(extracted)).thenReturn(systemIdNotNull);
        when(builder.and(chainNotNull, systemIdNotNull)).thenReturn(conjunction);

        Predicate predicate = captor.getValue().toPredicate(root, query, builder);

        assertThat(predicate, sameInstance(conjunction));
        verify(builder).isNotNull(chainPath);
        verify(builder).isNotNull(extracted);
    }

    // Each lookup differs only in the element property it filters by; this pins that mapping
    // and exercises the criteria predicate each one builds.

    private Predicate applyCapturedFindAllSpecification(String expectedProperty) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Specification<ChainElement>> captor = ArgumentCaptor.forClass(Specification.class);
        verify(elementRepository).findAll(captor.capture());
        return applySpecification(captor.getValue(), expectedProperty);
    }

    private Predicate applyCapturedExistsSpecification(String expectedProperty) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Specification<ChainElement>> captor = ArgumentCaptor.forClass(Specification.class);
        verify(elementRepository).exists(captor.capture());
        return applySpecification(captor.getValue(), expectedProperty);
    }

    private Predicate applySpecification(Specification<ChainElement> specification, String expectedProperty) {
        Root<ChainElement> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder builder = mock(CriteriaBuilder.class);
        Path<Object> chainPath = mock(Path.class);
        Path<String> propertiesPath = mock(Path.class);
        Expression<String> extracted = mock(Expression.class);
        Expression<String> literal = mock(Expression.class);
        Predicate predicate = mock(Predicate.class);
        Predicate conjunction = mock(Predicate.class);

        when(root.get(CHAIN_ATTRIBUTE)).thenReturn(chainPath);
        when(root.<String>get(PROPERTIES_ATTRIBUTE)).thenReturn(propertiesPath);
        when(builder.literal(anyString())).thenReturn(literal);
        when(builder.function(eq(JSON_EXTRACT_FUNCTION), eq(String.class), any(), any())).thenReturn(extracted);
        lenient().when(builder.isNotNull(any())).thenReturn(predicate);
        lenient().when(builder.equal(any(Expression.class), anyString())).thenReturn(predicate);
        when(builder.and(any(Predicate.class), any(Predicate.class))).thenReturn(conjunction);

        Predicate result = specification.toPredicate(root, query, builder);

        ArgumentCaptor<String> literalCaptor = ArgumentCaptor.forClass(String.class);
        verify(builder).literal(literalCaptor.capture());
        assertThat(literalCaptor.getValue(), equalTo(expectedProperty));
        verify(builder).isNotNull(chainPath);
        return result;
    }

    @Test
    @DisplayName("isSystemUsedByElement filters elements by the service id property")
    void isSystemUsedByElementFiltersBySystemId() {
        when(elementRepository.exists(ArgumentMatchers.<Specification<ChainElement>>any())).thenReturn(true);

        assertThat(elementHelperService.isSystemUsedByElement("system-1"), is(true));
        applyCapturedExistsSpecification(CamelOptions.SYSTEM_ID);
    }

    @Test
    @DisplayName("isSpecificationGroupUsedByElement filters elements by the specification group property")
    void isSpecificationGroupUsedByElementFiltersByGroupId() {
        when(elementRepository.exists(ArgumentMatchers.<Specification<ChainElement>>any())).thenReturn(false);

        assertThat(elementHelperService.isSpecificationGroupUsedByElement("group-1"), is(false));
        applyCapturedExistsSpecification(CamelOptions.SPECIFICATION_GROUP_ID);
    }

    @Test
    @DisplayName("isSystemModelUsedByElement filters elements by the specification property")
    void isSystemModelUsedByElementFiltersByModelId() {
        when(elementRepository.exists(ArgumentMatchers.<Specification<ChainElement>>any())).thenReturn(true);

        assertThat(elementHelperService.isSystemModelUsedByElement("model-1"), is(true));
        applyCapturedExistsSpecification(CamelOptions.MODEL_ID);
    }

    @Test
    @DisplayName("findBySystemIdAndOperationId filters elements by the operation property")
    void findBySystemIdAndOperationIdFiltersByOperationId() {
        when(elementRepository.findAll(ArgumentMatchers.<Specification<ChainElement>>any())).thenReturn(List.of());

        elementHelperService.findBySystemIdAndOperationId("system-1", "operation-1");
        applyCapturedFindAllSpecification(CamelOptions.OPERATION_ID);
    }

    @Test
    @DisplayName("findBySystemIdAndSpecificationGroupId filters elements by the specification group property")
    void findBySystemIdAndSpecificationGroupIdFiltersByGroupId() {
        when(elementRepository.findAll(ArgumentMatchers.<Specification<ChainElement>>any())).thenReturn(List.of());

        elementHelperService.findBySystemIdAndSpecificationGroupId("system-1", "group-1");
        applyCapturedFindAllSpecification(CamelOptions.SPECIFICATION_GROUP_ID);
    }

    @Test
    @DisplayName("findBySystemId filters elements by the service id property")
    void findBySystemIdFiltersBySystemId() {
        when(elementRepository.findAll(ArgumentMatchers.<Specification<ChainElement>>any())).thenReturn(List.of());

        elementHelperService.findBySystemId("system-1");
        applyCapturedFindAllSpecification(CamelOptions.SYSTEM_ID);
    }

    @Test
    @DisplayName("findByContextServiceId filters elements by the context service property")
    void findByContextServiceIdFiltersByContextServiceId() {
        when(elementRepository.findAll(ArgumentMatchers.<Specification<ChainElement>>any())).thenReturn(List.of());

        elementHelperService.findByContextServiceId("context-1");
        applyCapturedFindAllSpecification(CamelOptions.CONTEXT_SYSTEM_ID);
    }

    @Test
    @DisplayName("findBySystemAndModelId returns the chains of the elements it finds")
    void findBySystemAndModelIdFiltersByModelId() {
        Chain chain = chain("chain-1");
        when(elementRepository.findAll(ArgumentMatchers.<Specification<ChainElement>>any()))
                .thenReturn(List.of(element("system-1", chain)));

        assertThat(elementHelperService.findBySystemAndModelId("system-1", "model-1"), contains(chain));
        applyCapturedFindAllSpecification(CamelOptions.MODEL_ID);
    }
}

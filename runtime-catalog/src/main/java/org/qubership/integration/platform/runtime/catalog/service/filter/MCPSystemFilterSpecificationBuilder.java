package org.qubership.integration.platform.runtime.catalog.service.filter;

import jakarta.persistence.criteria.*;
import org.apache.poi.util.StringUtil;
import org.qubership.integration.platform.runtime.catalog.model.filter.FilterCondition;
import org.qubership.integration.platform.runtime.catalog.model.filter.FilterFeature;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.mcp.MCPSystem;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.FilterRequestDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static java.util.Objects.isNull;

@Component
public class MCPSystemFilterSpecificationBuilder {
    private final FilterConditionPredicateBuilderFactory filterConditionPredicateBuilderFactory;

    @Autowired
    public MCPSystemFilterSpecificationBuilder(
            FilterConditionPredicateBuilderFactory filterConditionPredicateBuilderFactory
    ) {
        this.filterConditionPredicateBuilderFactory = filterConditionPredicateBuilderFactory;
    }

    public Specification<MCPSystem> buildSearchAndFilters(String searchString, Collection<FilterRequestDTO> filters) {
        return (root, query, criteriaBuilder) -> {
            Predicate searchPredicate = StringUtil.isBlank(searchString)
                    ? criteriaBuilder.conjunction()
                    : buildSearch(searchString, root, criteriaBuilder);
            Predicate filterPredicate = isNull(filters) || filters.isEmpty()
                    ? criteriaBuilder.conjunction()
                    : buildFilters(filters, root, criteriaBuilder);
            return criteriaBuilder.and(searchPredicate, filterPredicate);
        };
    }

    private Predicate buildSearch(
            String searchString,
            Root<MCPSystem> root,
            CriteriaBuilder criteriaBuilder
    ) {
        Collection<FilterRequestDTO> filters = buildFiltersFromSearchString(searchString);
        return build(filters, CriteriaBuilder::or, root, criteriaBuilder);
    }

    private Predicate buildFilters(
            Collection<FilterRequestDTO> filters,
            Root<MCPSystem> root,
            CriteriaBuilder criteriaBuilder
    ) {
        return build(filters, CriteriaBuilder::and, root, criteriaBuilder);
    }

    private List<FilterRequestDTO> buildFiltersFromSearchString(String searchString) {
        return Stream.of(
                FilterFeature.ID,
                FilterFeature.NAME,
                FilterFeature.DESCRIPTION,
                FilterFeature.IDENTIFIER,
                FilterFeature.INSTRUCTIONS,
                FilterFeature.LABELS
        ).map(feature -> FilterRequestDTO
                .builder()
                .feature(feature)
                .value(searchString)
                .condition(FilterCondition.CONTAINS)
                .build()
        ).toList();
    }

    public Predicate build(
            Collection<FilterRequestDTO> filters,
            BiFunction<CriteriaBuilder, Predicate[], Predicate> predicateAccumulator,
            Root<MCPSystem> root,
            CriteriaBuilder criteriaBuilder
    ) {
        Predicate[] predicates = filters.stream()
                .map(filter -> buildPredicate(root, criteriaBuilder, filter))
                .toArray(Predicate[]::new);
        return predicateAccumulator.apply(criteriaBuilder, predicates);
    }

    private Predicate buildPredicate(
            Root<MCPSystem> root,
            CriteriaBuilder criteriaBuilder,
            FilterRequestDTO filter
    ) {
        var conditionPredicateBuilder = filterConditionPredicateBuilderFactory
                .<String>getPredicateBuilder(criteriaBuilder, filter.getCondition());
        String value = filter.getValue();
        return switch (filter.getFeature()) {
            case ID -> conditionPredicateBuilder.apply(root.get("id"), value);
            case NAME -> conditionPredicateBuilder.apply(root.get("name"), value);
            case DESCRIPTION -> conditionPredicateBuilder.apply(root.get("description"), value);
            case INSTRUCTIONS -> conditionPredicateBuilder.apply(root.get("instructions"), value);
            case IDENTIFIER -> conditionPredicateBuilder.apply(root.get("identifier"), value);
            case LABELS -> {
                Predicate predicate = conditionPredicateBuilder.apply(getJoin(root, "labels").get("name"), value);
                boolean negativeLabelFilter =
                        filter.getCondition() == FilterCondition.IS_NOT
                                || filter.getCondition() == FilterCondition.DOES_NOT_CONTAIN;

                yield negativeLabelFilter
                        ? criteriaBuilder.or(predicate, criteriaBuilder.isNull(getJoin(root, "labels").get("name")))
                        : predicate;
            }
            default -> throw new IllegalStateException("Unexpected filter feature: " + filter.getFeature());
        };
    }

    private Join<MCPSystem, ?> getJoin(Root<MCPSystem> root, String attributeName) {
        return root.getJoins().stream()
                .filter(join -> join.getAttribute().getName().equals(attributeName))
                .findAny()
                .orElseGet(() -> root.join(attributeName, JoinType.LEFT));
    }
}

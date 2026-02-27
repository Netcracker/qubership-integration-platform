package org.qubership.integration.platform.runtime.catalog.service.filter.liveexchange;

import org.qubership.integration.platform.runtime.catalog.model.filter.FilterCondition;
import org.qubership.integration.platform.runtime.catalog.model.filter.FilterFeature;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.FilterRequestDTO;

import java.util.Collection;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Function;

public abstract class AbstractEntityFilter<E, T> {
    private final Map<FilterCondition, BiPredicate<T, String>> conditions;

    private final Map<FilterFeature, Function<E, T>> extractors;

    public AbstractEntityFilter(Map<FilterCondition, BiPredicate<T, String>> conditions,
            Map<FilterFeature, Function<E, T>> extractors) {
        this.conditions = conditions;
        this.extractors = extractors;
    }

    protected Collection<FilterFeature> getFieldsToApply() {
        return extractors.keySet();
    }

    protected T getFieldValue(E entity, FilterRequestDTO filter) {
        return extractors.get(filter.getFeature()).apply(entity);
    }

    protected boolean isApplicable(FilterRequestDTO filter) {
        return getFieldsToApply().contains(filter.getFeature());
    }

    public boolean apply(E entity, FilterRequestDTO filter) {
        if (isApplicable(filter)) {
            return conditions.get(filter.getCondition()).test(getFieldValue(entity, filter), filter.getValue());
        }
        return true;
    }
}

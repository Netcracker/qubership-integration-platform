package org.qubership.integration.platform.runtime.catalog.service.filter.liveexchange;

import com.google.common.collect.ImmutableMap;
import org.qubership.integration.platform.runtime.catalog.model.filter.FilterCondition;
import org.qubership.integration.platform.runtime.catalog.model.filter.FilterFeature;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.engine.LiveExchangeExtDTO;
import org.springframework.stereotype.Component;

import java.util.function.BiPredicate;
import java.util.function.Function;

@Component
public class LongFieldFilter extends AbstractEntityFilter<LiveExchangeExtDTO, Long> {
    private static final BiPredicate<Long, String> IS_AFTER = (fieldValue,
            filterValue) -> fieldValue > Long.parseLong(filterValue);
    private static final BiPredicate<Long, String> IS_BEFORE = (fieldValue,
            filterValue) -> fieldValue < Long.parseLong(filterValue);

    public LongFieldFilter() {
        super(
                ImmutableMap.<FilterCondition, BiPredicate<Long, String>>builder()
                        .put(FilterCondition.IS_AFTER, IS_AFTER)
                        .put(FilterCondition.IS_BEFORE, IS_BEFORE)
                        .put(FilterCondition.IS_WITHIN,
                                (fieldValue, filterValue) -> {
                                    String[] range = String.valueOf(filterValue).split(",");
                                    return fieldValue >= Long.parseLong(range[0])
                                            && fieldValue <= Long.parseLong(range[1]);
                                })
                        .put(FilterCondition.GREATER_THAN, IS_AFTER)
                        .put(FilterCondition.LESS_THAN, IS_BEFORE)
                        .build(),
                ImmutableMap.<FilterFeature, Function<LiveExchangeExtDTO, Long>>builder()
                        .put(FilterFeature.SESSION_STARTED, (entity) -> entity.getSessionStartTime())
                        .put(FilterFeature.SESSION_DURATION, (entity) -> entity.getSessionDuration())
                        .put(FilterFeature.EXCHANGE_DURATION, (entity) -> entity.getDuration())
                        .build());
    }
}

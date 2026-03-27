package org.qubership.integration.platform.runtime.catalog.service.filter.liveexchange;

import com.google.common.collect.ImmutableMap;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.runtime.catalog.model.filter.FilterCondition;
import org.qubership.integration.platform.runtime.catalog.model.filter.FilterFeature;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.engine.LiveExchangeExtDTO;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Function;

@Component
public class StringFieldFilter extends AbstractEntityFilter<LiveExchangeExtDTO, String> {
    public StringFieldFilter() {
        super(
                ImmutableMap.<FilterCondition, BiPredicate<String, String>>builder()
                        .put(FilterCondition.CONTAINS,
                                (fieldValue, filterValue) -> StringUtils.containsIgnoreCase(fieldValue, filterValue))
                        .put(FilterCondition.DOES_NOT_CONTAIN,
                                (fieldValue, filterValue) -> !StringUtils.containsIgnoreCase(fieldValue, filterValue))
                        .put(FilterCondition.STARTS_WITH,
                                (fieldValue, filterValue) -> StringUtils.startsWithIgnoreCase(fieldValue, filterValue))
                        .put(FilterCondition.ENDS_WITH,
                                (fieldValue, filterValue) -> StringUtils.endsWithIgnoreCase(fieldValue, filterValue))
                        .put(FilterCondition.IS,
                                (fieldValue, filterValue) -> Objects.equals(fieldValue, filterValue))
                        .put(FilterCondition.IS_NOT,
                                (fieldValue, filterValue) -> !Objects.equals(fieldValue, filterValue))
                        .build(),
                ImmutableMap.<FilterFeature, Function<LiveExchangeExtDTO, String>>builder()
                        .put(FilterFeature.SESSION_ID, (entity) -> entity.getSessionId())
                        .put(FilterFeature.CHAIN_NAME, (entity) -> entity.getChainName())
                        .put(FilterFeature.MAIN_THREAD, (entity) -> entity.getMain().toString())
                        .put(FilterFeature.POD_IP, (entity) -> entity.getPodIp())
                        .build());
    }
}

package org.qubership.integration.platform.runtime.catalog.service.filter.liveexchange;

import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.FilterRequestDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.engine.LiveExchangeExtDTO;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class LiveExchangesFilterService {
    private final List<AbstractEntityFilter<LiveExchangeExtDTO, ?>> entityFilters;

    public List<LiveExchangeExtDTO> applyFilters(List<LiveExchangeExtDTO> exchanges, List<FilterRequestDTO> filters) {
        return CollectionUtils.emptyIfNull(exchanges).stream().filter(exchange -> applyFilters(exchange, filters))
                .toList();
    }

    public boolean applyFilters(LiveExchangeExtDTO exchange, List<FilterRequestDTO> filters) {
        return CollectionUtils.emptyIfNull(filters).stream().allMatch(filter -> applyFilter(exchange, filter));
    }

    private boolean applyFilter(LiveExchangeExtDTO exchange, FilterRequestDTO filter) {
        return entityFilters.stream().allMatch(entityFilters -> entityFilters.apply(exchange, filter));
    }
}

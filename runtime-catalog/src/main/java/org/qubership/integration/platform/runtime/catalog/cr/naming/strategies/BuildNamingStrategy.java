package org.qubership.integration.platform.runtime.catalog.cr.naming.strategies;

import org.qubership.integration.platform.runtime.catalog.cr.naming.NamingStrategy;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component("buildNamingStrategy")
public class BuildNamingStrategy implements NamingStrategy<BuildNamingContext> {
    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy.MM.dd-HHmmssSSS'Z'")
                    .withZone(ZoneId.from(ZoneOffset.UTC));

    @Override
    public String getName(BuildNamingContext context) {
        return DATE_TIME_FORMATTER.format(context.getTimestamp());
    }
}

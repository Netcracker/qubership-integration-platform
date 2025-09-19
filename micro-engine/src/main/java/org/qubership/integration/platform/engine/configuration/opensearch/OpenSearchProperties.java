package org.qubership.integration.platform.engine.configuration.opensearch;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithConverter;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import org.qubership.integration.platform.engine.opensearch.ism.model.time.TimeValue;

@ConfigMapping(prefix = "qip.opensearch")
public interface OpenSearchProperties {
    ClientProperties client();

    WriteProperties write();

    IndexProperties index();

    RolloverProperties rollover();

    interface ClientProperties {
        String urls();

        @WithDefault("opensearch")
        String host();

        @WithDefault("9200")
        Integer port();

        @WithDefault("http")
        String protocol();

        @WithDefault("")
        String userName();

        @WithDefault("")
        String password();

        @WithDefault("")
        String prefix();
    }

    interface WriteProperties {
        BatchProperties batch();

        RetryProperties retry();
    }

    interface BatchProperties {
        @WithDefault("100")

        Integer count();
    }

    interface RetryProperties {
        TimeoutProperties timeout();
    }

    interface TimeoutProperties {
        @WithDefault("100")
        Integer minimum();

        @WithDefault("300000")
        Integer maximum();
    }

    interface IndexProperties {
        ElementsProperties elements();
    }

    interface ElementsProperties {
        String name();

        @WithDefault("3")
        Integer shards();
    }

    interface RolloverProperties {
        @WithName("min_index_age")
        @WithDefault("1d")
        @WithConverter(StringToTimeValueConverter.class)
        TimeValue minIndexAge();

        @WithName("min_index_size")
        @WithDefault("")
        String minIndexSize();

        @WithName("min_rollover_age_to_delete")
        @WithDefault("14d")
        @WithConverter(StringToTimeValueConverter.class)
        TimeValue minRolloverAgeToDelete();
    }
}

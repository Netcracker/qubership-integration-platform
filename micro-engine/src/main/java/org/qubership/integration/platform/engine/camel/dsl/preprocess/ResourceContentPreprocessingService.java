package org.qubership.integration.platform.engine.camel.dsl.preprocess;

import io.quarkus.arc.All;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

@Unremovable
@ApplicationScoped
public class ResourceContentPreprocessingService {
    private final List<ResourceContentPreprocessor> preprocessors;

    @Inject
    public ResourceContentPreprocessingService(@All List<ResourceContentPreprocessor> preprocessors) {
        this.preprocessors = preprocessors;
    }

    public String preprocess(String content) throws Exception {
        String result = content;
        for (ResourceContentPreprocessor preprocessor : preprocessors) {
            result = preprocessor.apply(result);
        }
        return result;
    }
}

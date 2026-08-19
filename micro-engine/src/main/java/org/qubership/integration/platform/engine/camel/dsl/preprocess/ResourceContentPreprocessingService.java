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

    /**
     * Runs only the preprocessors that are independent of the presence of beans in Camel registry
     * Used to resolve placeholders in bean definitions during {@code preParseRoute}.
     */
    public String preprocessForPreParse(String content) throws Exception {
        String result = content;
        for (ResourceContentPreprocessor preprocessor : preprocessors) {
            if (preprocessor.runsInPreParse()) {
                result = preprocessor.apply(result);
            }
        }
        return result;
    }
}

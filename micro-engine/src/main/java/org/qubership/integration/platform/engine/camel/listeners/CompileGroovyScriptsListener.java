package org.qubership.integration.platform.engine.camel.listeners;

import jakarta.enterprise.inject.spi.CDI;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.k.Runtime;
import org.apache.camel.k.listener.AbstractPhaseListener;
import org.qubership.integration.platform.engine.service.groovy.GroovyScriptCompilationService;

// Runs after the routes are loaded and before any of them starts, ahead of PredeployCheckListener,
// so the endpoints of a chain whose script does not compile are not checked.
@Slf4j
public class CompileGroovyScriptsListener extends AbstractPhaseListener {
    public CompileGroovyScriptsListener() {
        super(Runtime.Phase.Starting);
    }

    @Override
    protected void accept(Runtime runtime) {
        try {
            CDI.current().select(GroovyScriptCompilationService.class).get().compileRoutes(runtime.getCamelContext());
        } catch (Exception exception) {
            log.error("Failed to check the groovy scripts of the loaded routes. The engine will not start.", exception);
            throw new RuntimeException(exception);
        }
    }
}

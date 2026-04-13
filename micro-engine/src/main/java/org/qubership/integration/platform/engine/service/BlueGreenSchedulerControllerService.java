package org.qubership.integration.platform.engine.service;

import com.netcracker.cloud.bluegreen.api.model.BlueGreenState;
import io.quarkus.arc.Unremovable;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import static java.util.Objects.nonNull;

@ApplicationScoped
@Unremovable
public class BlueGreenSchedulerControllerService {
    @Inject
    BlueGreenStateService blueGreenStateService;

    @Inject
    IntegrationRuntimeService integrationRuntimeService;

    @PostConstruct
    public void init() {
        this.blueGreenStateService.subscribe(this::resolveBlueGreenState);
    }

    private synchronized void resolveBlueGreenState(
        BlueGreenState previousState,
        BlueGreenState actualState
    ) {
        if (nonNull(previousState) && nonNull(previousState.getCurrent())) { // skip initial state event
            boolean isPrevActive = BlueGreenStateService.isActive(previousState);
            boolean isActualActive = BlueGreenStateService.isActive(actualState);

            // active -> other = disable
            if (isPrevActive && !isActualActive) {
                integrationRuntimeService.suspendAllSchedulers();
            } else {
                // other -> active = resume
                if (!isPrevActive && isActualActive) {
                    integrationRuntimeService.resumeAllSchedulers();
                }
            }
        }
    }
}

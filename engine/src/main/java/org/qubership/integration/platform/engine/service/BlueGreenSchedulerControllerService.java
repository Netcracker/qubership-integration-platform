package org.qubership.integration.platform.engine.service;

import com.netcracker.cloud.bluegreen.api.model.BlueGreenState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import static java.util.Objects.nonNull;

@Service
public class BlueGreenSchedulerControllerService {
    private final BlueGreenStateService blueGreenStateService;
    private final IntegrationRuntimeService integrationRuntimeService;

    @Autowired
    public BlueGreenSchedulerControllerService(
        BlueGreenStateService blueGreenStateService,
        IntegrationRuntimeService integrationRuntimeService
    ) {
        this.blueGreenStateService = blueGreenStateService;
        this.integrationRuntimeService = integrationRuntimeService;

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

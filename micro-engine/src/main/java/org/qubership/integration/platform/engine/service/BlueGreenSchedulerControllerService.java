package org.qubership.integration.platform.engine.service;

import com.netcracker.cloud.bluegreen.api.model.BlueGreenState;
import io.quarkus.arc.Unremovable;
import io.quarkus.vertx.ConsumeEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import static java.util.Objects.nonNull;

@ApplicationScoped
@Unremovable
public class BlueGreenSchedulerControllerService {

    @Inject
    QuartzSchedulerService quartzSchedulerService;

    @ConsumeEvent(value = BlueGreenStateService.BLUE_GREEN_STATE_EVENT_ADDRESS)
    public void onStateChange(BlueGreenStateService.BlueGreenStateChange stateChange) {
        resolveBlueGreenState(stateChange.oldState(), stateChange.newState());
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
                quartzSchedulerService.suspendAllSchedulers();
            } else {
                // other -> active = resume
                if (!isPrevActive && isActualActive) {
                    quartzSchedulerService.resumeAllSchedulers();
                }
            }
        }
    }
}

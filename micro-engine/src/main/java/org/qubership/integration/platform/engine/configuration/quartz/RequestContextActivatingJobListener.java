/*
 * Copyright 2024-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.qubership.integration.platform.engine.configuration.quartz;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import lombok.extern.slf4j.Slf4j;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.listeners.JobListenerSupport;

/**
 * Quartz worker threads are owned by Quartz itself and never pass through Quarkus' Vert.x
 * request handling, so the CDI {@code RequestScoped} context is never active on them. Any bean
 * that relies on it — for example {@code SecurityIdentityAssociation}, which
 * {@code WhoAmIProvider} uses during exchange context propagation — fails with
 * {@code ContextNotActiveException} as soon as a chain is triggered by the scheduler instead of
 * an HTTP request.
 *
 * <p>This listener activates a fresh request context for the duration of each Quartz job
 * execution and terminates it afterward, mirroring what the {@code @ActivateRequestContext}
 * interceptor does for a single method call.
 */
@Slf4j
public class RequestContextActivatingJobListener extends JobListenerSupport {
    public static final String NAME = "request-context-activating-job-listener";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void jobToBeExecuted(JobExecutionContext context) {
        activateIfNeeded();
    }

    @Override
    public void jobExecutionVetoed(JobExecutionContext context) {
        terminateIfActive();
    }

    @Override
    public void jobWasExecuted(JobExecutionContext context, JobExecutionException jobException) {
        terminateIfActive();
    }

    private void activateIfNeeded() {
        ManagedContext requestContext = Arc.container().requestContext();
        if (!requestContext.isActive()) {
            requestContext.activate();
        }
    }

    private void terminateIfActive() {
        ManagedContext requestContext = Arc.container().requestContext();
        if (requestContext.isActive()) {
            try {
                requestContext.terminate();
            } catch (Exception e) {
                log.warn("Failed to terminate request context after quartz job execution", e);
            }
        }
    }
}

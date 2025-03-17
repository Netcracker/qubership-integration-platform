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

package org.qubership.integration.platform.engine.camel;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerEvent;
import io.github.resilience4j.core.EventConsumer;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Processor;
import org.apache.camel.Route;
import org.apache.camel.component.resilience4j.ResilienceProcessor;
import org.apache.camel.component.resilience4j.ResilienceReifier;
import org.apache.camel.model.CircuitBreakerDefinition;
import org.qubership.integration.platform.engine.model.logging.LogLoggingLevel;
import org.qubership.integration.platform.engine.service.debugger.CamelDebugger;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

@Slf4j
public class CustomResilienceReifier extends ResilienceReifier {
    public CustomResilienceReifier(Route route, CircuitBreakerDefinition definition) {
        super(route, definition);
    }

    @Override
    public Processor createProcessor() throws Exception {
        ResilienceProcessor processor = (ResilienceProcessor) super.createProcessor();
        CircuitBreakerConfig config = getCircuitBreakerConfig(processor);
        CircuitBreaker circuitBreaker = CircuitBreaker.of(definition.getId(), config);
        CircuitBreaker.EventPublisher eventPublisher = circuitBreaker.getEventPublisher();
        configureEventPublisher(eventPublisher, processor, circuitBreaker);
        processor.setCircuitBreaker(circuitBreaker);
        return processor;
    }

    private CircuitBreakerConfig getCircuitBreakerConfig(ResilienceProcessor processor) {
        return CircuitBreakerConfig.custom()
                .automaticTransitionFromOpenToHalfOpenEnabled(processor.isCircuitBreakerTransitionFromOpenToHalfOpenEnabled())
                .failureRateThreshold(processor.getCircuitBreakerFailureRateThreshold())
                .minimumNumberOfCalls(processor.getCircuitBreakerMinimumNumberOfCalls())
                .permittedNumberOfCallsInHalfOpenState(processor.getCircuitBreakerPermittedNumberOfCallsInHalfOpenState())
                .slidingWindowSize(processor.getCircuitBreakerSlidingWindowSize())
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.valueOf(processor.getCircuitBreakerSlidingWindowType()))
                .slowCallDurationThreshold(Duration.ofSeconds(parseLong(definition.getResilience4jConfiguration().getSlowCallDurationThreshold())))
                .slowCallRateThreshold(processor.getCircuitBreakerSlowCallRateThreshold())
                .waitDurationInOpenState(Duration.ofSeconds(processor.getCircuitBreakerWaitDurationInOpenState()))
                .writableStackTraceEnabled(processor.isCircuitBreakerWritableStackTraceEnabled())
                .build();
    }

    private void configureEventPublisher(
            CircuitBreaker.EventPublisher eventPublisher,
            ResilienceProcessor processor,
            CircuitBreaker circuitBreaker
    ) {
        eventPublisher.onStateTransition(event -> {
            CircuitBreaker.StateTransition stateTransition = event.getStateTransition();
            CircuitBreaker.State fromState = stateTransition.getFromState();
            CircuitBreaker.State toState = stateTransition.getToState();
            LogLoggingLevel level = getLoggingLevel(processor);
            if (CircuitBreaker.State.OPEN.equals(toState)) {
                if (level.isWarnLevel()) {
                    log.warn("Circuit breaker changed state from {} to {}.", fromState, toState);
                }
            } else if (level.isInfoLevel()) {
                log.info("Circuit breaker changed state from {} to {}.", fromState, toState);
            }
        });

        eventPublisher.onSuccess(callIfLogLevel(processor, LogLoggingLevel::isInfoLevel, event -> log.info(
                "Circuit breaker recorded a successful call. Elapsed time: {} ms.",
                event.getElapsedDuration().toMillis())));
        eventPublisher.onError(callIfLogLevel(processor, LogLoggingLevel::isInfoLevel, event -> log.info(
                "Circuit breaker recorded an error: '{}'. Elapsed time: {} ms.",
                event.getThrowable(),
                event.getElapsedDuration().toMillis())));
        eventPublisher.onReset(callIfLogLevel(processor, LogLoggingLevel::isInfoLevel, event -> log.info(
                "Circuit breaker reset")));
        eventPublisher.onIgnoredError(callIfLogLevel(processor, LogLoggingLevel::isInfoLevel, event -> log.info(
                "Circuit breaker recorded an error which has been ignored: '{}'. Elapsed time: {} ms.",
                event.getThrowable(),
                event.getElapsedDuration().toMillis())));
        eventPublisher.onCallNotPermitted(callIfLogLevel(processor, LogLoggingLevel::isInfoLevel, event -> log.info(
                "Circuit breaker recorded a call which was not permitted. Circuit breaker state is {}.",
                circuitBreaker.getState())));
        eventPublisher.onFailureRateExceeded(callIfLogLevel(processor, LogLoggingLevel::isWarnLevel, event -> log.warn(
                "Circuit breaker exceeded failure rate threshold. Current failure rate: {}.",
                event.getFailureRate())));
        eventPublisher.onSlowCallRateExceeded(callIfLogLevel(processor, LogLoggingLevel::isWarnLevel, event -> log.warn(
                "Circuit breaker exceeded slow call rate threshold. Current slow call rate: {}.",
                event.getSlowCallRate())));
    }

    private <T extends CircuitBreakerEvent> EventConsumer<T> callIfLogLevel(
            ResilienceProcessor processor,
            Predicate<LogLoggingLevel> levelPredicate,
            Consumer<T> consumer
    ) {
        return event -> {
            LogLoggingLevel level = getLoggingLevel(processor);
            if (levelPredicate.test(level)) {
                consumer.accept(event);
            }
        }; 
    }

    private static LogLoggingLevel getLoggingLevel(ResilienceProcessor processor) {
        return Optional.ofNullable(processor.getCamelContext().getDebugger())
                .map(CamelDebugger.class::cast)
                .map(CamelDebugger::getRelatedProperties)
                .map(properties -> properties.getActualRuntimeProperties().getLogLoggingLevel())
                .orElse(LogLoggingLevel.WARN);
    }
}

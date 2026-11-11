package org.qubership.integration.platform.ai.integration.catalog.client;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import jakarta.interceptor.InterceptorBinding;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Waits for a renewed turn bearer before the catalog call sends its request, so a token renewal
 * never counts as a slow or failed HTTP call. The catalog clients carry no Fault Tolerance {@code
 * @Timeout} because Quarkus applies it outside this interceptor; the 10 s HTTP timeout comes from
 * {@code quarkus.rest-client.catalog-api.read-timeout}.
 */
@InterceptorBinding
@Retention(RUNTIME)
@Target(TYPE)
public @interface AwaitUsableTurnBearer {}

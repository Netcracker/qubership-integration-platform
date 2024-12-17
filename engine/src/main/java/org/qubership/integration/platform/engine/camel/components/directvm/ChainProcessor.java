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

package org.qubership.integration.platform.engine.camel.components.directvm;

import org.apache.camel.*;
import org.apache.camel.support.DefaultExchange;
import org.apache.camel.support.ExchangeHelper;
import org.apache.camel.support.processor.DelegateAsyncProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class ChainProcessor extends DelegateAsyncProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(ChainProcessor.class);
    private final ChainEndpoint endpoint;

    public ChainProcessor(Processor processor, ChainEndpoint endpoint) {
        super(processor);
        this.endpoint = endpoint;
    }

    @Override
    public boolean process(final Exchange exchange, final AsyncCallback callback) {
        // need to use a copy of the incoming exchange, so we route using this camel context
        final Exchange copy = prepareExchange(exchange);

        ClassLoader current = Thread.currentThread().getContextClassLoader();
        boolean changed = false;
        try {
            // set TCCL to application context class loader if given
            ClassLoader appClassLoader = endpoint.getCamelContext().getApplicationContextClassLoader();
            if (appClassLoader != null) {
                LOG.trace("Setting Thread ContextClassLoader to {}", appClassLoader);
                Thread.currentThread().setContextClassLoader(appClassLoader);
                changed = true;
            }

            final boolean chgd = changed;
            return processor.process(copy, new AsyncCallback() {
                @Override
                public void done(boolean done) {
                    try {
                        // restore TCCL if it was changed during processing
                        if (chgd) {
                            LOG.trace("Restoring Thread ContextClassLoader to {}", current);
                            Thread.currentThread().setContextClassLoader(current);
                        }
                        // make sure to copy results back
                        ExchangeHelper.copyResults(exchange, copy);
                    } finally {
                        // must call callback when we are done
                        callback.done(done);
                    }
                }
            });
        } finally {
            // restore TCCL if it was changed during processing
            if (changed) {
                LOG.trace("Restoring Thread ContextClassLoader to {}", current);
                Thread.currentThread().setContextClassLoader(current);
            }
        }
    }

    /**
     * Strategy to prepare exchange for being processed by this consumer
     *
     * @param  exchange the exchange
     * @return          the exchange to process by this consumer.
     */
    private Exchange prepareExchange(Exchange exchange) {
        // send a new copied exchange with new camel context (do not handover completions)
        Exchange newExchange = copyExchangeAndSetCamelContext(exchange, endpoint.getCamelContext(), false);
        // set the from endpoint
        newExchange.getExchangeExtension().setFromEndpoint(endpoint);
        // The StreamCache created by the child routes must not be
        // closed by the unit of work of the child route, but by the unit of
        // work of the parent route or grand parent route or grand grand parent route ...(in case of nesting).
        // Set therefore the unit of work of the  parent route as stream cache unit of work,
        // if it is not already set.
        if (newExchange.getProperty(ExchangePropertyKey.STREAM_CACHE_UNIT_OF_WORK) == null) {
            newExchange.setProperty(ExchangePropertyKey.STREAM_CACHE_UNIT_OF_WORK, exchange.getUnitOfWork());
        }
        return newExchange;
    }

    /**
     * Copies the exchange but the copy will be tied to the given context
     *
     * @param  exchange the source exchange
     * @param  context  the camel context
     * @param  handover whether to handover on completions from the source to the copy
     * @return          a copy with the given camel context
     */
    private static Exchange copyExchangeAndSetCamelContext(Exchange exchange, CamelContext context, boolean handover) {
        DefaultExchange answer = new DefaultExchange(context, exchange.getPattern());
        if (exchange.hasProperties()) {
            answer.getExchangeExtension().setProperties(safeCopyProperties(exchange.getProperties()));
        }
        exchange.getExchangeExtension().copyInternalProperties(answer);
        // safe copy message history using a defensive copy
        List<MessageHistory> history
                = (List<MessageHistory>) exchange.getProperty(ExchangePropertyKey.MESSAGE_HISTORY);
        if (history != null) {
            // use thread-safe list as message history may be accessed concurrently
            answer.setProperty(ExchangePropertyKey.MESSAGE_HISTORY, new CopyOnWriteArrayList<>(history));
        }

        if (handover) {
            // Need to hand over the completion for async invocation
            exchange.getExchangeExtension().handoverCompletions(answer);
        }
        answer.setIn(exchange.getIn().copy());
        if (exchange.hasOut()) {
            answer.setOut(exchange.getOut().copy());
        }
        answer.setException(exchange.getException());
        return answer;
    }

    private static Map<String, Object> safeCopyProperties(Map<String, Object> properties) {
        if (properties == null) {
            return null;
        }
        return new ConcurrentHashMap<>(properties);
    }

    @Override
    public String toString() {
        return "ChainProcessor[" + processor + "]";
    }
}

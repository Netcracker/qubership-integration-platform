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

package org.qubership.integration.platform.engine.camel.components.servlet;

import org.apache.camel.http.common.HttpConsumer;
import org.apache.camel.support.RestConsumerContextPathMatcher;
import org.apache.camel.support.RestConsumerContextPathMatcher.ConsumerPath;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nonnull;

/**
 * Based on {@link RestConsumerContextPathMatcher}
 */
public class RestConsumerContextPathCustomMatcher {

    /**
     * Finds the best matching of the list of consumer paths that should service the incoming
     * request.
     *
     * @param requestMethod the incoming request HTTP method
     * @param requestPath   the incoming request context path
     * @param consumerPaths the list of consumer context path details
     * @return the best matched consumer, or <tt>null</tt> if none could be determined.
     */
    public static ConsumerPath<HttpConsumer> matchBestPath(String requestMethod, String requestPath, List<ConsumerPath<HttpConsumer>> consumerPaths) {
        List<ConsumerPath<HttpConsumer>> candidates = new ArrayList<>();

        // first match by http method
        for (ConsumerPath<HttpConsumer> entry : consumerPaths) {
            if (matchRestMethod(requestMethod, entry.getRestrictMethod())) {
                candidates.add(entry);
            }
        }

        // then see if we got a direct match
        ConsumerPath<HttpConsumer> answer = matchRestPathAndTime(requestPath, candidates);

        // we could not find a direct match, and if the request is OPTIONS then we need all candidates
        if (answer == null && isOptionsMethod(requestMethod)) {
            candidates.clear();
            candidates.addAll(consumerPaths);

            // then try again to see if we can find a direct match
            answer = matchRestPathAndTime(requestPath, candidates);
        }

        // if there are no wildcards, then select the matching with the longest path
        boolean noWildcards = candidates.stream().allMatch(p -> countWildcards(p.getConsumerPath()) == 0);
        if (noWildcards) {
            // grab first which is the longest that matched the request path
            answer = matchNewerConsumer(candidates.stream()
                    .filter(c -> RestConsumerContextPathMatcher.matchPath(requestPath, c.getConsumerPath(),
                            c.isMatchOnUriPrefix()))
                    // TODO not working with (isMatchOnUriPrefix == true)
                    //.sorted(Comparator.comparingInt(o -> -1 * o.getConsumerPath().length()))
                    .toList());
        }

        // then match by wildcard path
        if (answer == null) {
            Iterator<ConsumerPath<HttpConsumer>> it = candidates.iterator();
            while (it.hasNext()) {
                ConsumerPath<HttpConsumer> consumer = it.next();
                // filter non matching paths
                if (!matchRestPath(requestPath, consumer.getConsumerPath(), true)) {
                    it.remove();
                }
            }

            // if there is multiple candidates with wildcards then pick anyone with the least number of wildcards
            int bestWildcard = Integer.MAX_VALUE;
            List<ConsumerPath<HttpConsumer>> wildcardCandidates = null;
            if (candidates.size() > 1) {
                it = candidates.iterator();
                while (it.hasNext()) {
                    ConsumerPath<HttpConsumer> entry = it.next();
                    int wildcards = countWildcards(entry.getConsumerPath());
                    if (wildcards > 0) {
                        if (wildcardCandidates == null || wildcards < bestWildcard) {
                            wildcardCandidates = new ArrayList<>();
                            wildcardCandidates.add(entry);
                            bestWildcard = wildcards;
                        } else {
                            if (wildcards == bestWildcard) {
                                wildcardCandidates.add(entry);
                            }
                        }
                    }
                }

                if (wildcardCandidates != null) {
                    // pick the best among the wildcards
                    answer = matchNewerConsumer(wildcardCandidates);
                }
            }

            // if there is one left then its our answer
            if (answer == null && candidates.size() == 1) {
                answer = candidates.getFirst();
            }
        }

        return answer;
    }

    private static ConsumerPath<HttpConsumer> matchRestPathAndTime(String requestPath, @Nonnull List<ConsumerPath<HttpConsumer>> candidates) {
        List<ConsumerPath<HttpConsumer>> sameCandidates = candidates.stream()
                .filter(path -> matchRestPath(requestPath, path.getConsumerPath(), false)).toList();
        return matchNewerConsumer(sameCandidates);
    }

    private static ConsumerPath<HttpConsumer> matchNewerConsumer(List<ConsumerPath<HttpConsumer>> consumers) {
        return consumers.stream()
                .filter(consumer -> consumer.getConsumer() instanceof ServletCustomConsumer)
                .max((c1, c2) -> Math.toIntExact(
                        ((ServletCustomConsumer) c1.getConsumer()).getCreationTime()
                                - ((ServletCustomConsumer) c2.getConsumer()).getCreationTime()))
                .orElse(null);
    }

    /**
     * Matches the given request HTTP method with the configured HTTP method of the consumer.
     *
     * @param method   the request HTTP method
     * @param restrict the consumer configured HTTP restrict method
     * @return <tt>true</tt> if matched, <tt>false</tt> otherwise
     */
    private static boolean matchRestMethod(String method, String restrict) {
        if (restrict == null) {
            return true;
        }

        return restrict.toLowerCase(Locale.ENGLISH).contains(method.toLowerCase(Locale.ENGLISH));
    }

    /**
     * Is the request method OPTIONS
     *
     * @return <tt>true</tt> if matched, <tt>false</tt> otherwise
     */
    private static boolean isOptionsMethod(String method) {
        return "options".equalsIgnoreCase(method);
    }

    /**
     * Matches the given request path with the configured consumer path
     *
     * @param requestPath  the request path
     * @param consumerPath the consumer path which may use { } tokens
     * @return <tt>true</tt> if matched, <tt>false</tt> otherwise
     */
    private static boolean matchRestPath(String requestPath, String consumerPath,
                                         boolean wildcard) {
        // deal with null parameters
        if (requestPath == null && consumerPath == null) {
            return true;
        }
        if (requestPath == null || consumerPath == null) {
            return false;
        }

        // remove starting/ending slashes
        if (requestPath.startsWith("/")) {
            requestPath = requestPath.substring(1);
        }
        if (requestPath.endsWith("/")) {
            requestPath = requestPath.substring(0, requestPath.length() - 1);
        }
        // remove starting/ending slashes
        if (consumerPath.startsWith("/")) {
            consumerPath = consumerPath.substring(1);
        }
        if (consumerPath.endsWith("/")) {
            consumerPath = consumerPath.substring(0, consumerPath.length() - 1);
        }

        // split using single char / is optimized in the jdk
        String[] requestPaths = requestPath.split("/");
        String[] consumerPaths = consumerPath.split("/");

        // must be same number of path's
        if (requestPaths.length != consumerPaths.length) {
            return false;
        }

        for (int i = 0; i < requestPaths.length; i++) {
            String p1 = requestPaths[i];
            String p2 = consumerPaths[i];

            if (wildcard && p2.startsWith("{") && p2.endsWith("}")) {
                // always matches
                continue;
            }

            if (!RestConsumerContextPathMatcher.matchPath(p1, p2, false)) {
                return false;
            }
        }

        // assume matching
        return true;
    }

    /**
     * Counts the number of wildcards in the path
     *
     * @param consumerPath the consumer path which may use { } tokens
     * @return number of wildcards, or <tt>0</tt> if no wildcards
     */
    private static int countWildcards(String consumerPath) {
        int wildcards = 0;

        // remove starting/ending slashes
        if (consumerPath.startsWith("/")) {
            consumerPath = consumerPath.substring(1);
        }
        if (consumerPath.endsWith("/")) {
            consumerPath = consumerPath.substring(0, consumerPath.length() - 1);
        }

        String[] consumerPaths = consumerPath.split("/");
        for (String p2 : consumerPaths) {
            if (p2.startsWith("{") && p2.endsWith("}")) {
                wildcards++;
            }
        }

        return wildcards;
    }
}

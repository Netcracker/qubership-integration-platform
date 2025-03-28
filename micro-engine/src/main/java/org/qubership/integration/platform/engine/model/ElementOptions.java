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

package org.qubership.integration.platform.engine.model;

public class ElementOptions {

    // kafka
    public static final String BROKERS = "brokers";
    public static final String TOPICS = "topics";
    public static final String GROUP_ID = "groupId";
    public static final String SECURITY_PROTOCOL = "securityProtocol";
    public static final String SASL_MECHANISM = "saslMechanism";
    public static final String SASL_JAAS_CONFIG = "saslJaasConfig";

    // amqp
    public static final String EXCHANGE = "exchange";
    public static final String QUEUES = "queues";
    public static final String ADDRESSES = "addresses";
    public static final String USERNAME = "username";
    public static final String PASSWORD = "password";
    public static final String VHOST = "vhost";
    public static final String SSL = "sslProtocol";

    // pubsub
    public static final String PROJECT_ID = "projectId";
    public static final String DESTINATION_NAME = "destinationName";

    public static final String CONNECTION_SOURCE_TYPE_PROP = "connectionSourceType";

    // generic maas
    public static final String MAAS_DEPLOYMENT_CLASSIFIER_PROP = "maasClassifier";

    private ElementOptions() {
    }
}

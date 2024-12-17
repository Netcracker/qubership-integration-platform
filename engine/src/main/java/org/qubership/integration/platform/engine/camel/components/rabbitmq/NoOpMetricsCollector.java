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

package org.qubership.integration.platform.engine.camel.components.rabbitmq;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.MetricsCollector;

public class NoOpMetricsCollector implements MetricsCollector {
    @Override
    public void newConnection(Connection connection) {

    }

    @Override
    public void closeConnection(Connection connection) {

    }

    @Override
    public void newChannel(Channel channel) {

    }

    @Override
    public void closeChannel(Channel channel) {

    }

    @Override
    public void basicPublish(Channel channel) {

    }

    @Override
    public void consumedMessage(Channel channel, long deliveryTag, boolean autoAck) {

    }

    @Override
    public void consumedMessage(Channel channel, long deliveryTag, String consumerTag) {

    }

    @Override
    public void basicAck(Channel channel, long deliveryTag, boolean multiple) {

    }

    @Override
    public void basicNack(Channel channel, long deliveryTag) {

    }

    @Override
    public void basicReject(Channel channel, long deliveryTag) {

    }

    @Override
    public void basicConsume(Channel channel, String consumerTag, boolean autoAck) {

    }

    @Override
    public void basicCancel(Channel channel, String consumerTag) {

    }
}

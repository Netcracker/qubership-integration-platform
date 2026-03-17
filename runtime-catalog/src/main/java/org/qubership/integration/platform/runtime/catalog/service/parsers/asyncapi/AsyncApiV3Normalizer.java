package org.qubership.integration.platform.runtime.catalog.service.parsers.asyncapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.*;
import org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.components.Components;
import org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.components.SchemaObject;
import org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.v3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class AsyncApiV3Normalizer {

    private static final String CHANNELS_REF_PREFIX = "#/channels/";
    private static final String COMPONENTS_MESSAGES_PREFIX = "#/components/messages/";
    private static final String MESSAGES_SEGMENT = "/messages/";

    private final ObjectMapper objectMapper;

    @Autowired
    public AsyncApiV3Normalizer(@Qualifier("primaryObjectMapper") ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AsyncapiSpecification normalize(AsyncapiV3Specification v3) {
        AsyncapiSpecification v2 = new AsyncapiSpecification();
        v2.setInfo(normalizeInfo(v3.getInfo()));
        v2.setServers(normalizeServers(v3.getServers()));
        v2.setComponents(normalizeComponents(v3.getComponents()));

        Map<String, V3Channel> v3Channels = v3.getChannels();
        Map<String, Channel> v2Channels = new LinkedHashMap<>();

        if (v3.getOperations() != null && v3Channels != null) {
            for (Map.Entry<String, V3Operation> entry : v3.getOperations().entrySet()) {
                V3Operation v3Op = entry.getValue();
                String channelKey = resolveChannelKey(v3Op);
                if (channelKey == null) {
                    log.warn("Operation '{}' has no channel reference, skipping", entry.getKey());
                    continue;
                }

                V3Channel v3Channel = v3Channels.get(channelKey);
                if (v3Channel == null) {
                    log.warn("Channel '{}' not found for operation '{}'", channelKey, entry.getKey());
                    continue;
                }

                String v2ChannelPath = v3Channel.getAddress() != null ? v3Channel.getAddress() : channelKey;

                Channel v2Channel = v2Channels.computeIfAbsent(v2ChannelPath, k -> {
                    Channel ch = new Channel();
                    ch.setDescription(v3Channel.getDescription());
                    ch.setParameters(v3Channel.getParameters());
                    ch.setBindings(v3Channel.getBindings());
                    return ch;
                });

                OperationObject v2Op = convertOperation(entry.getKey(), v3Op, v3Channel, v3Channels);
                v2Op.setAction(v3Op.getAction());

                if ("send".equals(v3Op.getAction())) {
                    v2Channel.setPublish(v2Op);
                } else {
                    v2Channel.setSubscribe(v2Op);
                }

                normalizeReplyOperation(entry.getKey(), v3Op, v3, v2Channels);
            }
        }

        v2.setChannels(v2Channels);
        return v2;
    }

    private Info normalizeInfo(Info v3Info) {
        return v3Info != null ? v3Info : new Info();
    }

    private Map<String, Server> normalizeServers(Map<String, V3Server> v3Servers) {
        if (v3Servers == null || v3Servers.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Server> servers = new LinkedHashMap<>();
        for (Map.Entry<String, V3Server> entry : v3Servers.entrySet()) {
            V3Server v3Server = entry.getValue();
            Server server = new Server(
                    v3Server.toUrl(),
                    v3Server.getProtocol(),
                    v3Server.getMaasInstanceId()
            );
            servers.put(entry.getKey(), server);
        }
        return servers;
    }

    private Components normalizeComponents(Map<String, Object> v3Components) {
        if (v3Components == null || v3Components.isEmpty()) {
            return new Components();
        }
        return objectMapper.convertValue(v3Components, Components.class);
    }

    private String resolveChannelKey(V3Operation v3Op) {
        if (v3Op.getChannel() == null || v3Op.getChannel().getRef() == null) {
            return null;
        }
        String ref = v3Op.getChannel().getRef();
        if (ref.startsWith(CHANNELS_REF_PREFIX)) {
            return ref.substring(CHANNELS_REF_PREFIX.length());
        }
        return ref;
    }

    private OperationObject convertOperation(String operationKey, V3Operation v3Op,
                                             V3Channel v3Channel,
                                             Map<String, V3Channel> v3Channels) {
        OperationObject v2Op = new OperationObject();
        v2Op.setSummary(v3Op.getSummary());
        v2Op.setOperationId(v3Op.getOperationId() != null ? v3Op.getOperationId() : operationKey);
        v2Op.setMaasClassifierName(v3Op.getMaasClassifierName());
        v2Op.setMessage(convertMessages(v3Op, v3Channel, v3Channels));
        return v2Op;
    }

    private Message convertMessages(V3Operation v3Op, V3Channel v3Channel,
                                    Map<String, V3Channel> v3Channels) {
        List<Map<String, Object>> opMessages = v3Op.getMessages();
        if (opMessages != null && !opMessages.isEmpty()) {
            return convertMessageList(opMessages, v3Channels);
        }
        if (v3Channel.getMessages() != null && !v3Channel.getMessages().isEmpty()) {
            return convertChannelMessages(v3Channel.getMessages());
        }
        return null;
    }

    private Message convertMessageList(List<Map<String, Object>> messageList,
                                       Map<String, V3Channel> v3Channels) {
        if (messageList.size() == 1) {
            return convertSingleMessageEntry(messageList.get(0), v3Channels);
        }
        Message message = new Message();
        List<Map<String, Object>> oneOf = new ArrayList<>();
        for (Map<String, Object> msg : messageList) {
            oneOf.add(resolveMessageRef(msg, v3Channels));
        }
        message.setOneOf(oneOf);
        return message;
    }

    private Message convertChannelMessages(Map<String, Object> channelMessages) {
        if (channelMessages.size() == 1) {
            Map.Entry<String, Object> entry = channelMessages.entrySet().iterator().next();
            return convertChannelMessageEntry(entry.getKey(), entry.getValue());
        }
        Message message = new Message();
        List<Map<String, Object>> oneOf = new ArrayList<>();
        for (Map.Entry<String, Object> entry : channelMessages.entrySet()) {
            oneOf.add(channelMessageToMap(entry.getKey(), entry.getValue()));
        }
        message.setOneOf(oneOf);
        return message;
    }

    private Message convertSingleMessageEntry(Map<String, Object> msgMap,
                                              Map<String, V3Channel> v3Channels) {
        if (msgMap.containsKey("$ref")) {
            String ref = (String) msgMap.get("$ref");
            Message resolved = tryResolveChannelMessageRef(ref, v3Channels);
            if (resolved != null) {
                return resolved;
            }
            Message message = new Message();
            message.set$ref(convertComponentRef(ref));
            return message;
        }
        return buildMessageFromMap(msgMap, (String) msgMap.get("name"));
    }

    private Message convertChannelMessageEntry(String key, Object value) {
        if (value instanceof Map<?, ?> msgMap) {
            if (msgMap.containsKey("$ref")) {
                Message message = new Message();
                message.set$ref(convertComponentRef((String) msgMap.get("$ref")));
                return message;
            }
            return buildMessageFromMap(asStringObjectMap(msgMap), key);
        }
        Message message = new Message();
        message.setName(key);
        return message;
    }

    private Message buildMessageFromMap(Map<String, Object> msgMap, String name) {
        Message message = new Message();
        if (msgMap.containsKey("payload")) {
            message.setPayload(objectMapper.convertValue(msgMap.get("payload"), SchemaObject.class));
        }
        if (msgMap.containsKey("headers")) {
            message.setHeaders(objectMapper.convertValue(msgMap.get("headers"), SchemaObject.class));
        }
        if (name != null) {
            message.setName(name);
        }
        return message;
    }

    private Message tryResolveChannelMessageRef(String ref, Map<String, V3Channel> v3Channels) {
        if (ref == null || !ref.startsWith(CHANNELS_REF_PREFIX) || !ref.contains(MESSAGES_SEGMENT)) {
            return null;
        }
        String withoutPrefix = ref.substring(CHANNELS_REF_PREFIX.length());
        int msgIdx = withoutPrefix.indexOf(MESSAGES_SEGMENT);
        if (msgIdx < 0) {
            return null;
        }
        String channelKey = withoutPrefix.substring(0, msgIdx);
        String messageName = withoutPrefix.substring(msgIdx + MESSAGES_SEGMENT.length());

        V3Channel channel = v3Channels.get(channelKey);
        if (channel == null || channel.getMessages() == null) {
            return null;
        }
        Object msgValue = channel.getMessages().get(messageName);
        if (msgValue == null) {
            return null;
        }
        if (msgValue instanceof Map<?, ?> msgMap) {
            if (msgMap.containsKey("$ref")) {
                return null;
            }
            return buildMessageFromMap(asStringObjectMap(msgMap), messageName);
        }
        return null;
    }

    private Map<String, Object> resolveMessageRef(Map<String, Object> msgMap,
                                                  Map<String, V3Channel> v3Channels) {
        if (!msgMap.containsKey("$ref")) {
            return new LinkedHashMap<>(msgMap);
        }
        String ref = (String) msgMap.get("$ref");
        Message resolved = tryResolveChannelMessageRef(ref, v3Channels);
        if (resolved != null) {
            Map<String, Object> result = new LinkedHashMap<>();
            if (resolved.getPayload() != null) {
                result.put("payload", objectMapper.convertValue(resolved.getPayload(), Map.class));
            }
            if (resolved.getHeaders() != null) {
                result.put("headers", objectMapper.convertValue(resolved.getHeaders(), Map.class));
            }
            if (resolved.getName() != null) {
                result.put("name", resolved.getName());
            }
            return result;
        }
        Map<String, Object> result = new LinkedHashMap<>(msgMap);
        result.put("$ref", convertComponentRef(ref));
        return result;
    }

    private Map<String, Object> channelMessageToMap(String key, Object value) {
        if (value instanceof Map<?, ?> msgMap) {
            Map<String, Object> result = new LinkedHashMap<>(asStringObjectMap(msgMap));
            if (result.containsKey("$ref")) {
                result.put("$ref", convertComponentRef((String) result.get("$ref")));
            }
            return result;
        }
        return Map.of("name", key);
    }

    private String convertComponentRef(String ref) {
        if (ref == null || ref.startsWith(COMPONENTS_MESSAGES_PREFIX)) {
            return ref;
        }
        if (ref.startsWith(CHANNELS_REF_PREFIX) && ref.contains(MESSAGES_SEGMENT)) {
            String messageName = ref.substring(ref.lastIndexOf(MESSAGES_SEGMENT) + MESSAGES_SEGMENT.length());
            return COMPONENTS_MESSAGES_PREFIX + messageName;
        }
        return ref;
    }

    private void normalizeReplyOperation(String operationKey, V3Operation v3Op,
                                         AsyncapiV3Specification v3,
                                         Map<String, Channel> v2Channels) {
        V3Reply reply = v3Op.getReply();
        if (reply == null || reply.getChannel() == null || reply.getChannel().getRef() == null) {
            return;
        }

        String replyChannelKey = resolveRefToKey(reply.getChannel().getRef());
        if (replyChannelKey == null) {
            return;
        }

        Map<String, V3Channel> v3Channels = v3.getChannels();
        V3Channel replyV3Channel = v3Channels != null ? v3Channels.get(replyChannelKey) : null;
        String replyAddress = replyV3Channel != null && replyV3Channel.getAddress() != null
                ? replyV3Channel.getAddress() : replyChannelKey;

        Channel replyV2Channel = v2Channels.computeIfAbsent(replyAddress, k -> {
            Channel ch = new Channel();
            if (replyV3Channel != null) {
                ch.setDescription(replyV3Channel.getDescription());
                ch.setParameters(replyV3Channel.getParameters());
                ch.setBindings(replyV3Channel.getBindings());
            }
            return ch;
        });

        OperationObject replyOp = new OperationObject();
        replyOp.setSummary(v3Op.getSummary() != null ? v3Op.getSummary() + " (reply)" : "reply");
        String baseId = v3Op.getOperationId() != null ? v3Op.getOperationId() : operationKey;
        replyOp.setOperationId(baseId + "Reply");

        if (reply.getMessages() != null && !reply.getMessages().isEmpty()) {
            replyOp.setMessage(convertMessageList(reply.getMessages(), v3Channels));
        } else if (replyV3Channel != null && replyV3Channel.getMessages() != null) {
            replyOp.setMessage(convertChannelMessages(replyV3Channel.getMessages()));
        }

        MethodType replyMethod = "send".equals(v3Op.getAction())
                ? MethodType.SUBSCRIBE : MethodType.PUBLISH;
        if (replyMethod == MethodType.PUBLISH) {
            replyV2Channel.setPublish(replyOp);
        } else {
            replyV2Channel.setSubscribe(replyOp);
        }
    }

    private String resolveRefToKey(String ref) {
        if (ref.startsWith(CHANNELS_REF_PREFIX)) {
            return ref.substring(CHANNELS_REF_PREFIX.length());
        }
        return ref;
    }

    // Safe cast: Jackson deserializes JSON objects as Map<String, Object>
    @SuppressWarnings("unchecked")
    private Map<String, Object> asStringObjectMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}

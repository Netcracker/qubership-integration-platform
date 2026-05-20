package org.qubership.integration.platform.engine.opensearch.ism.deserializers;

import com.fasterxml.jackson.core.ObjectCodec;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.opensearch.ism.model.actions.Action;
import org.qubership.integration.platform.engine.opensearch.ism.model.actions.ActionRetry;
import org.qubership.integration.platform.engine.opensearch.ism.model.actions.ReadOnlyAction;
import org.qubership.integration.platform.engine.opensearch.ism.model.time.TimeValue;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ActionDeserializerTest {

    private final ActionDeserializer deserializer = new ActionDeserializer();

    @Mock
    private com.fasterxml.jackson.core.JsonParser jsonParser;

    @Mock
    private ObjectCodec codec;

    @Test
    void shouldReturnNullWhenTreeNodeIsNull() throws Exception {
        when(jsonParser.readValueAsTree()).thenReturn(null);

        Action result = deserializer.deserialize(jsonParser, null);

        assertNull(result);
    }

    @Test
    void shouldDeserializeActionAndPopulateTimeoutAndRetry() throws Exception {
        ObjectNode rootNode = JsonNodeFactory.instance.objectNode();
        ObjectNode actionNode = JsonNodeFactory.instance.objectNode();
        ObjectNode timeoutNode = JsonNodeFactory.instance.objectNode();
        ObjectNode retryNode = JsonNodeFactory.instance.objectNode();

        rootNode.set("read_only", actionNode);
        rootNode.set("timeout", timeoutNode);
        rootNode.set("retry", retryNode);

        ReadOnlyAction action = mock(ReadOnlyAction.class);
        TimeValue timeout = mock(TimeValue.class);
        ActionRetry retry = mock(ActionRetry.class);

        when(jsonParser.readValueAsTree()).thenReturn(rootNode);
        when(jsonParser.getCodec()).thenReturn(codec);

        doReturn(action).when(codec).treeToValue(actionNode, ReadOnlyAction.class);
        doReturn(timeout).when(codec).treeToValue(timeoutNode, TimeValue.class);
        doReturn(retry).when(codec).treeToValue(retryNode, ActionRetry.class);

        Action result = deserializer.deserialize(jsonParser, null);

        assertSame(action, result);
        verify(action).setTimeout(timeout);
        verify(action).setRetry(retry);
    }

    @Test
    void shouldThrowInvalidFormatExceptionWhenNodeIsNotObjectNode() throws Exception {
        TextNode node = TextNode.valueOf("wrong");
        when(jsonParser.readValueAsTree()).thenReturn(node);
        when(jsonParser.getCurrentName()).thenReturn("action");

        InvalidFormatException exception = assertThrows(
                InvalidFormatException.class,
                () -> deserializer.deserialize(jsonParser, null)
        );

        assertEquals("Wrong action field type", exception.getOriginalMessage());
        assertEquals(TimeValue.class, exception.getTargetType());
    }

    @Test
    void shouldThrowInvalidFormatExceptionWhenActionTypeIsUnsupported() throws Exception {
        ObjectNode rootNode = JsonNodeFactory.instance.objectNode();
        rootNode.putObject("unsupported");

        when(jsonParser.readValueAsTree()).thenReturn(rootNode);

        InvalidFormatException exception = assertThrows(
                InvalidFormatException.class,
                () -> deserializer.deserialize(jsonParser, null)
        );

        assertEquals("Unsupported action type", exception.getOriginalMessage());
        assertEquals(TimeValue.class, exception.getTargetType());
    }
}

package org.qubership.integration.platform.engine.camel.components.graphql;

import org.apache.camel.AsyncCallback;
import org.apache.camel.Exchange;
import org.apache.camel.component.graphql.GraphqlEndpoint;
import org.apache.camel.http.base.HttpOperationFailedException;
import org.apache.camel.util.json.JsonObject;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.*;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Headers;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;

import java.net.URI;

import static org.apache.camel.Exchange.HTTP_RESPONSE_CODE;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class GraphqlCustomProducerTest {

    @Mock
    GraphqlEndpoint endpoint;
    @Mock
    CloseableHttpClient httpClient;
    @Mock
    CloseableHttpResponse response;
    @Mock
    Exchange exchange;
    @Mock
    AsyncCallback callback;

    @BeforeEach
    public void setUp() {
        endpoint = mock(GraphqlEndpoint.class);
        httpClient = mock(CloseableHttpClient.class);
        response = mock(CloseableHttpResponse.class);
        exchange = MockExchanges.defaultExchange();
        callback = mock(AsyncCallback.class);
    }

    @Test
    void shouldExecuteHttpPostAndSetBodyAndHeadersWhenResponseSuccessful() throws Exception {
        URI uri = URI.create("http://localhost/graphql");

        when(endpoint.getHttpclient()).thenReturn(httpClient);
        when(endpoint.getHttpUri()).thenReturn(uri);

        when(endpoint.getQuery()).thenReturn("query { test }");

        JsonObject vars = new JsonObject();
        vars.put("a", "b");
        when(endpoint.getVariables()).thenReturn(vars);

        when(response.getCode()).thenReturn(200);
        when(response.getEntity()).thenReturn(new StringEntity("{\"data\":1}", ContentType.APPLICATION_JSON));
        when(response.getHeaders()).thenReturn(new Header[]{
                new BasicHeader("X-Resp", "r1"),
                new BasicHeader("X-Req", "override")
        });

        when(httpClient.execute(any(ClassicHttpRequest.class))).thenReturn(response);

        exchange.getMessage().setHeader("X-Req", "v1");
        exchange.getMessage().setHeader(Headers.GQL_QUERY_HEADER, "must-be-excluded");
        exchange.getMessage().setHeader(Headers.GQL_VARIABLES_HEADER, "must-be-excluded");
        exchange.getMessage().setHeader(HttpHeaders.CONTENT_LENGTH, "123");
        exchange.getMessage().setHeader("X-NonString", 123);

        GraphqlCustomProducer producer = new GraphqlCustomProducer(endpoint);
        boolean result = producer.process(exchange, callback);

        assertTrue(result);

        assertEquals(200, exchange.getMessage().getHeader(HTTP_RESPONSE_CODE));
        assertEquals("{\"data\":1}", exchange.getMessage().getBody(String.class));

        assertEquals("r1", exchange.getMessage().getHeader("X-Resp"));
        assertEquals("override", exchange.getMessage().getHeader("X-Req"));

        ArgumentCaptor<ClassicHttpRequest> requestCaptor = ArgumentCaptor.forClass(ClassicHttpRequest.class);
        verify(httpClient).execute(requestCaptor.capture());

        assertInstanceOf(HttpPost.class, requestCaptor.getValue());
        HttpPost post = (HttpPost) requestCaptor.getValue();

        assertEquals("application/json", post.getFirstHeader(HttpHeaders.ACCEPT).getValue());
        assertEquals("gzip", post.getFirstHeader(HttpHeaders.ACCEPT_ENCODING).getValue());

        assertEquals("v1", post.getFirstHeader("X-Req").getValue());
        assertNull(post.getFirstHeader(Headers.GQL_QUERY_HEADER));
        assertNull(post.getFirstHeader(Headers.GQL_VARIABLES_HEADER));
        assertNull(post.getFirstHeader(HttpHeaders.CONTENT_LENGTH));
        assertNull(post.getFirstHeader("X-NonString"));

        HttpEntity reqEntity = post.getEntity();
        String reqBody = EntityUtils.toString(reqEntity);
        assertTrue(reqBody.contains("query { test }"));
        assertTrue(reqBody.contains("a"));

        verify(callback).done(true);
        assertNull(exchange.getException());
    }

    @Test
    void shouldSetHttpOperationFailedExceptionWhenStatusIsError() throws Exception {
        URI uri = URI.create("http://localhost/graphql");

        when(endpoint.getHttpclient()).thenReturn(httpClient);
        when(endpoint.getHttpUri()).thenReturn(uri);
        when(endpoint.getQuery()).thenReturn("query { err }");

        when(response.getCode()).thenReturn(500);
        when(response.getReasonPhrase()).thenReturn("Internal Server Error");
        when(response.getEntity()).thenReturn(new StringEntity("boom", ContentType.TEXT_PLAIN));
        when(response.getHeaders()).thenReturn(new Header[]{new BasicHeader("X-Err", "1")});

        when(httpClient.execute(any(ClassicHttpRequest.class))).thenReturn(response);

        GraphqlCustomProducer producer = new GraphqlCustomProducer(endpoint);
        boolean result = producer.process(exchange, callback);

        assertTrue(result);

        assertEquals(500, exchange.getMessage().getHeader(HTTP_RESPONSE_CODE));
        assertNotNull(exchange.getException());
        assertInstanceOf(HttpOperationFailedException.class, exchange.getException());

        verify(callback).done(true);
    }

    @Test
    void shouldReadQueryFromHeaderWhenEndpointQueryNullAndQueryHeaderProvided() throws Exception {
        URI uri = URI.create("http://localhost/graphql");

        when(endpoint.getHttpclient()).thenReturn(httpClient);
        when(endpoint.getHttpUri()).thenReturn(uri);

        when(endpoint.getQuery()).thenReturn(null);
        when(endpoint.getQueryHeader()).thenReturn("X-Query");

        when(response.getCode()).thenReturn(200);
        when(response.getEntity()).thenReturn(new StringEntity("ok", ContentType.TEXT_PLAIN));
        when(response.getHeaders()).thenReturn(new Header[0]);

        when(httpClient.execute(any(ClassicHttpRequest.class))).thenReturn(response);

        exchange.getIn().setHeader("X-Query", "query { fromHeader }");

        GraphqlCustomProducer producer = new GraphqlCustomProducer(endpoint);
        producer.process(exchange, callback);

        ArgumentCaptor<ClassicHttpRequest> requestCaptor = ArgumentCaptor.forClass(ClassicHttpRequest.class);
        verify(httpClient).execute(requestCaptor.capture());

        HttpPost post = (HttpPost) requestCaptor.getValue();
        String reqBody = EntityUtils.toString(post.getEntity());
        assertTrue(reqBody.contains("fromHeader"));

        verify(callback).done(true);
    }
}

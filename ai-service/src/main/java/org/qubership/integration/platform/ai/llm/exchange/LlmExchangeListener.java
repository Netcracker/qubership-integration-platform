package org.qubership.integration.platform.ai.llm.exchange;

import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.jboss.logmanager.MDC;
import org.qubership.integration.platform.ai.chat.ChatMdc;
import org.qubership.integration.platform.ai.compiler.CompilerSkillMdc;
import org.qubership.integration.platform.ai.configuration.AppConfig;

/**
 * Logs chat model request/response exchanges to a dedicated file logger
 * ({@code org.qubership.integration.platform.ai.llm.exchange}).
 *
 * <p>Embedding model calls are not covered. One user turn may produce multiple request/response
 * pairs (router, skill agents, tool repair retries).
 */
@ApplicationScoped
public class LlmExchangeListener implements ChatModelListener {

  static final String LOGGER_NAME = "org.qubership.integration.platform.ai.llm.exchange";
  static final String START_TIME_KEY = "llmExchangeStartMs";
  static final String CONTEXT_KEY = "llmExchangeContext";
  static final String EXCHANGE_ID_KEY = "llmExchangeId";

  private static final Logger LOG = Logger.getLogger(LOGGER_NAME);
  private static final Logger CAPTURE_LOG = Logger.getLogger(LOGGER_NAME + ".capture");

  private final AppConfig appConfig;
  private final LlmExchangeFormatter formatter;

  @Inject
  LlmExchangeListener(AppConfig appConfig, LlmExchangeFormatter formatter) {
    this.appConfig = appConfig;
    this.formatter = formatter;
  }

  @Override
  public void onRequest(ChatModelRequestContext requestContext) {
    if (!appConfig.llm().exchange().enabled()) {
      return;
    }
    requestContext.attributes().put(START_TIME_KEY, System.currentTimeMillis());
    var context = readMdcContext();
    String exchangeId = java.util.UUID.randomUUID().toString();
    requestContext.attributes().put(CONTEXT_KEY, context);
    requestContext.attributes().put(EXCHANGE_ID_KEY, exchangeId);
    CAPTURE_LOG.info(formatter.requestCapture(requestContext.chatRequest(), appConfig.llm().baseUrl(), exchangeId, context));
    LOG.info("exchangeId=" + exchangeId + " model=" + appConfig.llm().modelName() + " "
        + formatter.formatRequest(
            requestContext.chatRequest().messages(),
            context,
            -1,
            appConfig.llm().exchange().maxChars()));
  }

  @Override
  public void onResponse(ChatModelResponseContext responseContext) {
    if (!appConfig.llm().exchange().enabled()) {
      return;
    }
    CAPTURE_LOG.info(formatter.responseCapture(responseContext.chatResponse(),
        String.valueOf(responseContext.attributes().get(EXCHANGE_ID_KEY)),
        exchangeContext(responseContext.attributes()), durationMs(responseContext.attributes())));
    LOG.info("exchangeId=" + responseContext.attributes().get(EXCHANGE_ID_KEY)
        + " model=" + appConfig.llm().modelName() + " "
        + formatter.formatResponse(
            responseContext.chatResponse(),
            exchangeContext(responseContext.attributes()),
            durationMs(responseContext.attributes()),
            appConfig.llm().exchange().maxChars()));
  }

  @Override
  public void onError(ChatModelErrorContext errorContext) {
    if (!appConfig.llm().exchange().enabled()) {
      return;
    }
    CAPTURE_LOG.info(formatter.errorCapture(errorContext.error(),
        String.valueOf(errorContext.attributes().get(EXCHANGE_ID_KEY)),
        exchangeContext(errorContext.attributes()), durationMs(errorContext.attributes())));
    LOG.error("exchangeId=" + errorContext.attributes().get(EXCHANGE_ID_KEY)
        + " model=" + appConfig.llm().modelName() + " "
        + formatter.formatError(
            exchangeContext(errorContext.attributes()), durationMs(errorContext.attributes()), errorContext.error()));
  }

  static LlmExchangeMdcContext exchangeContext(java.util.Map<Object, Object> attributes) {
    return attributes.get(CONTEXT_KEY) instanceof LlmExchangeMdcContext context ? context : readMdcContext();
  }

  static LlmExchangeMdcContext readMdcContext() {
    return new LlmExchangeMdcContext(
        mdcOrNone(ChatMdc.CONVERSATION_ID),
        mdcOrNone(ChatMdc.SCENARIO_TYPE),
        mdcOrNone(CompilerSkillMdc.CAPABILITY_ID));
  }

  private static String mdcOrNone(String key) {
    Object value = MDC.get(key);
    if (value == null) {
      return "(none)";
    }
    String text = value.toString();
    return text.isBlank() ? "(none)" : text;
  }

  private static long durationMs(java.util.Map<Object, Object> attributes) {
    if (attributes == null) {
      return -1;
    }
    Object start = attributes.get(START_TIME_KEY);
    if (!(start instanceof Long startMs)) {
      return -1;
    }
    return System.currentTimeMillis() - startMs;
  }
}

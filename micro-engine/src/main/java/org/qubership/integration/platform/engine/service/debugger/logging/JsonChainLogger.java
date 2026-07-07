package org.qubership.integration.platform.engine.service.debugger.logging;

import io.quarkiverse.loggingjson.providers.StructuredArgument;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.apache.camel.http.base.HttpOperationFailedException;
import org.qubership.integration.platform.engine.errorhandling.errorcode.ErrorCode;
import org.qubership.integration.platform.engine.model.logging.LoggedPayloadValues;
import org.qubership.integration.platform.engine.service.VariablesService;
import org.qubership.integration.platform.engine.service.debugger.ChainRuntimePropertiesService;
import org.qubership.integration.platform.engine.service.debugger.tracing.TracingService;
import org.qubership.integration.platform.engine.service.debugger.util.PayloadExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static io.quarkiverse.loggingjson.providers.KeyValueStructuredArgument.kv;

@ApplicationScoped
@IfBuildProperty(name = "qip.logging.format", stringValue = "json", enableIfMissing = true)
public class JsonChainLogger extends AbstractChainLogger {
    public JsonChainLogger() {
        super(null, null, null, null, null);
    }

    @Inject
    public JsonChainLogger(TracingService tracingService,
            Instance<OriginatingBusinessIdProvider> originatingBusinessIdProvider,
            PayloadExtractor payloadExtractor,
            ChainRuntimePropertiesService chainRuntimePropertiesService,
            VariablesService variablesService) {
        super(tracingService, originatingBusinessIdProvider, payloadExtractor, chainRuntimePropertiesService,
                variablesService);
    }

    @Override
    public void logExchange(String message, LoggedPayloadValues loggedPayloadValues) {
        List<StructuredArgument> args = buildExchangeArguments(loggedPayloadValues);
        chainLogger.info(message, args.toArray());
    }

    @Override
    public void logError(String message, Exception exception, LoggedPayloadValues loggedPayloadValues) {
        List<StructuredArgument> args = buildExchangeArguments(loggedPayloadValues);
        enrichWithErrorCode(args, exception);
        chainLogger.error(String.format("%s %s", message, exception != null ? exception.getMessage() : ""),
                args.toArray());
    }

    @Override
    public void logErrorWithHttpParams(String message, ErrorCode errorCode, HttpLogParameters params,
            LoggedPayloadValues loggedPayloadValues) {
        List<StructuredArgument> args = buildExchangeArguments(loggedPayloadValues);
        enrichWithHttpArgs(args, params);
        enrichWithErrorCode(args, errorCode);

        chainLogger.error(message, args.toArray());
    }

    @Override
    public void logHttpParams(String message, HttpLogParameters params, LoggedPayloadValues loggedPayloadValues) {
        List<StructuredArgument> args = buildExchangeArguments(loggedPayloadValues);
        enrichWithHttpArgs(args, params);

        chainLogger.info(message, args.toArray());
    }

    @Override
    public void logFailedHttpOperation(LoggedPayloadValues loggedPayloadValues,
            HttpOperationFailedException httpException, long duration) {
        List<StructuredArgument> args = buildExchangeArguments(loggedPayloadValues);
        enrichWithHttpArgs(args, HttpLogParameters.createErrorResponse(httpException, duration));
        enrichWithErrorCode(args, httpException);

        chainLogger.error("HTTP request failed.", args.toArray());
    }

    @Override
    public void logFailedOperation(LoggedPayloadValues loggedPayloadValues,
            Exception exception, long duration) {
        List<StructuredArgument> args = buildExchangeArguments(loggedPayloadValues);
        enrichWithHttpArgs(args, HttpLogParameters.createResponse(duration));
        enrichWithErrorCode(args, exception);

        chainLogger.error("HTTP request failed.", args.toArray());
    }

    @Override
    public void logExternalServiceParams(String message, HttpLogParameters params,
            LoggedPayloadValues loggedPayloadValues,
            String externalServiceEnvName,
            String externalServiceAddress) {
        List<StructuredArgument> args = buildExchangeArguments(loggedPayloadValues);
        enrichWithExternalServiceMarkers(args, externalServiceEnvName, externalServiceAddress);
        if (params != null) {
            enrichWithHttpArgs(args, params);
        }
        chainLogger.info(message, args.toArray());
    }

    private void enrichWithHttpArgs(List<StructuredArgument> args, HttpLogParameters params) {
        args.add(kv("url", params.getTargetUrl()));
        args.add(kv("response_code", params.getResponseCode()));
        args.add(kv("response_time", params.getResponseTime()));
        args.add(kv("direction", params.getDirection()));
    }

    private void enrichWithErrorCode(List<StructuredArgument> args, Exception exception) {
        getErrorCode(exception).ifPresent(code -> args.add(kv("error_code", code.getFormattedCode())));
    }

    private void enrichWithErrorCode(List<StructuredArgument> args, ErrorCode errorCode) {
        args.add(kv("error_code", errorCode.getFormattedCode()));
    }

    private void enrichWithExternalServiceMarkers(List<StructuredArgument> args, String externalServiceEnvName,
            String externalServiceAddress) {
        args.add(kv("external_service_env_name", externalServiceEnvName));
        args.add(kv("external_service_address", externalServiceAddress));
    }

    private List<StructuredArgument> buildExchangeArguments(LoggedPayloadValues loggedPayloadValues) {
        List<StructuredArgument> result = new ArrayList<>();
        result.add(kv("exchange_headers", loggedPayloadValues.getHeaders()));
        result.add(kv("exchange_body", loggedPayloadValues.getBody()));
        result.add(kv("exchange_properties", loggedPayloadValues.getProperties()));
        return result;
    }

    private Optional<ErrorCode> getErrorCode(Exception exception) {
        return exception == null ? Optional.empty() : Optional.of(ErrorCode.match(exception));
    }
}

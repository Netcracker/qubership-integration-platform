package org.qubership.integration.platform.engine.routes.entrypoint.execution;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class SnapshotFixtureInteraction {
    private final String fixtureId;
    private final SnapshotFixtureResponse response;
    private final SnapshotFixtureRequestExpectation expectedRequest;
    private final List<SnapshotFixtureLogExpectation> expectedLogs;
    private final boolean expectedLogsDefined;
    private final List<SnapshotFixtureExchangeExpectation> expectedExchanges;
    private final boolean expectedExchangesDefined;
    private final String transitionToState;
    private final String expectedState;

    @JsonCreator
    public SnapshotFixtureInteraction(
            @JsonProperty("fixture") String fixtureId,
            @JsonProperty("response") SnapshotFixtureResponse response,
            @JsonProperty("expectedRequest") SnapshotFixtureRequestExpectation expectedRequest,
            @JsonProperty("expectedLogs") List<SnapshotFixtureLogExpectation> expectedLogs,
            @JsonProperty("expectedExchanges") List<SnapshotFixtureExchangeExpectation> expectedExchanges,
            @JsonProperty("transitionToState") String transitionToState,
            @JsonProperty("expectedState") String expectedState
    ) {
        this.fixtureId = requireNonBlank(fixtureId);
        this.response = response;
        this.expectedRequest = expectedRequest;
        this.expectedLogsDefined = expectedLogs != null;
        this.expectedLogs = expectedLogs == null ? List.of() : List.copyOf(expectedLogs);
        this.expectedExchangesDefined = expectedExchanges != null;
        this.expectedExchanges = expectedExchanges == null ? List.of() : List.copyOf(expectedExchanges);
        this.transitionToState = optionalNonBlank(transitionToState, "transitionToState");
        this.expectedState = optionalNonBlank(expectedState, "expectedState");
        if (response == null
                && expectedRequest == null
                && !expectedLogsDefined
                && !expectedExchangesDefined
                && this.transitionToState == null
                && this.expectedState == null) {
            throw new IllegalArgumentException(
                    "Snapshot fixture interaction must define a response, an expected request, expected logs, "
                            + "expected exchanges, a state transition, or an expected state."
            );
        }
    }

    public String getFixtureId() {
        return fixtureId;
    }

    public SnapshotFixtureResponse getResponse() {
        return response;
    }

    public SnapshotFixtureRequestExpectation getExpectedRequest() {
        return expectedRequest;
    }

    public List<SnapshotFixtureLogExpectation> getExpectedLogs() {
        return expectedLogs;
    }

    public boolean hasExpectedLogs() {
        return expectedLogsDefined;
    }

    public List<SnapshotFixtureExchangeExpectation> getExpectedExchanges() {
        return expectedExchanges;
    }

    public boolean hasExpectedExchanges() {
        return expectedExchangesDefined;
    }

    public String getTransitionToState() {
        return transitionToState;
    }

    public String getExpectedState() {
        return expectedState;
    }

    private static String requireNonBlank(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Snapshot fixture interaction fixture is missing.");
        }
        return value;
    }

    private static String optionalNonBlank(String value, String fieldName) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(
                    "Snapshot fixture interaction " + fieldName + " cannot be blank."
            );
        }
        return value;
    }
}

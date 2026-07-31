package org.qubership.integration.platform.runtime.catalog.service.designgenerator;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.model.designgenerator.DiagramLangType;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.qubership.integration.platform.runtime.catalog.model.designgenerator.DiagramOperationType.ACTIVATE;
import static org.qubership.integration.platform.runtime.catalog.model.designgenerator.DiagramOperationType.ELSE;
import static org.qubership.integration.platform.runtime.catalog.model.designgenerator.DiagramOperationType.END;
import static org.qubership.integration.platform.runtime.catalog.model.designgenerator.DiagramOperationType.LINE_WITH_ARROW_SOLID_RIGHT;
import static org.qubership.integration.platform.runtime.catalog.model.designgenerator.DiagramOperationType.START_ALT;

class SequenceDiagramBuilderTest {

    @Test
    void shouldReportNoContentWhenOnlyBlockFramingIsAppended() {
        SequenceDiagramBuilder builder = new SequenceDiagramBuilder();

        SequenceDiagramBuilder.Checkpoint checkpoint = builder.checkpoint();
        builder.append(START_ALT, "If");
        builder.append(ELSE, "Else");
        builder.append(END);

        assertFalse(builder.hasContentSince(checkpoint));
    }

    @Test
    void shouldReportContentWhenAnInteractionIsAppended() {
        SequenceDiagramBuilder builder = new SequenceDiagramBuilder();

        SequenceDiagramBuilder.Checkpoint checkpoint = builder.checkpoint();
        builder.append(START_ALT, "If");
        builder.append(LINE_WITH_ARROW_SOLID_RIGHT, "chain", "service", "Call");
        builder.append(END);

        assertTrue(builder.hasContentSince(checkpoint));
    }

    @Test
    void shouldNotCountActivationAsContent() {
        SequenceDiagramBuilder builder = new SequenceDiagramBuilder();

        SequenceDiagramBuilder.Checkpoint checkpoint = builder.checkpoint();
        builder.append(ACTIVATE, "chain");

        assertFalse(builder.hasContentSince(checkpoint));
    }

    @Test
    void shouldRestoreEverySourceWhenRevertingToCheckpoint() {
        SequenceDiagramBuilder builder = new SequenceDiagramBuilder();
        builder.append(LINE_WITH_ARROW_SOLID_RIGHT, "chain", "service", "Kept");

        SequenceDiagramBuilder.Checkpoint checkpoint = builder.checkpoint();
        Map<DiagramLangType, String> before = builder.build();

        builder.append(START_ALT, "Dropped");
        builder.append(END);
        builder.revertTo(checkpoint);

        assertEquals(before, builder.build());
    }

    @Test
    void shouldForgetContentWhenRevertingToCheckpoint() {
        SequenceDiagramBuilder builder = new SequenceDiagramBuilder();

        SequenceDiagramBuilder.Checkpoint checkpoint = builder.checkpoint();
        builder.append(LINE_WITH_ARROW_SOLID_RIGHT, "chain", "service", "Call");
        builder.revertTo(checkpoint);

        assertFalse(builder.hasContentSince(checkpoint));
    }
}

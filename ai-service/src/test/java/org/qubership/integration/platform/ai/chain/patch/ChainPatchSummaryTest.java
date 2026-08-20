package org.qubership.integration.platform.ai.chain.patch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chain.edit.CanonicalGraphDiff;
import org.qubership.integration.platform.ai.chain.edit.ChainEditAction;
import org.qubership.integration.platform.ai.chain.edit.ChainEditDisposition;
import org.qubership.integration.platform.ai.chain.edit.ChainEditIntent;
import org.qubership.integration.platform.ai.chain.edit.ChainEditSubgraphAssembly;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorCache;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorLoader;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorTestSupport;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ChainEditSubgraph;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ChainEditSubgraphBody;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ChainEditSubgraphElement;
import org.qubership.integration.platform.ai.qipknowledge.patch.EdgePatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOperation;
import org.qubership.integration.platform.ai.qipknowledge.patch.NodePatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.PropertyPatch;

/**
 * The card text is the last thing between a reader and a delete they cannot take back, so what it
 * must say -- and in what order -- is pinned here rather than left to whoever edits it next.
 */
class ChainPatchSummaryTest {

    @Test
    void saysSoWhenThePatchWouldWriteNothing() {
        String text = ChainPatchSummary.describe(graph(), patch(List.of(), List.of(), List.of()));

        assertEquals("The change is empty: nothing would be written.", text);
    }

    @Test
    void namesTheRemovedElementAndWarnsThatRemovalIsFinal() {
        String text = ChainPatchSummary.describe(
                graph(), patch(List.of(removeNode("audit")), List.of(), List.of()));

        assertTrue(text.contains("**Removes** Legacy audit log (script)"), text);
        assertTrue(
                text.contains("Removing cannot be undone. To keep a way back, save a snapshot first."),
                text);
    }

    /** Cutting a connection can be undone by drawing it again, so it carries no such warning. */
    @Test
    void namesBothEndsOfACutConnectionAndWarnsAboutNothing() {
        String text = ChainPatchSummary.describe(
                graph(), patch(List.of(), List.of(removeEdge("enrich->audit")), List.of()));

        assertTrue(text.contains("**Disconnects** Enrich payload (script) from Legacy audit log (script)"), text);
        assertFalse(text.contains("cannot be undone"), text);
    }

    /** Buried under a list of additions is how a card gets answered without being read. */
    @Test
    void putsRemovalsAheadOfEverythingElse() {
        String text = ChainPatchSummary.describe(
                graph(),
                patch(
                        List.of(removeNode("audit"), addNode("mapper", "mapper", "Map fields")),
                        List.of(),
                        List.of(changeProperty("enrich", "script", "return null;"))));

        assertTrue(text.indexOf("**Removes** Legacy audit log") < text.indexOf("**Adds** Map fields"), text);
        assertTrue(
                text.indexOf("**Removes** Legacy audit log") < text.indexOf("Enrich payload (script) — script"),
                text);
    }

    @Test
    void numbersEveryActionInOneSequenceAcrossKinds() {
        String text = ChainPatchSummary.describe(
                graph(),
                patch(
                        List.of(removeNode("audit"), addNode("mapper", "mapper", "Map fields")),
                        List.of(),
                        List.of(changeProperty("enrich", "script", "return null;"))));

        assertTrue(text.contains("1. **Removes** Legacy audit log (script)"), text);
        assertTrue(text.contains("2. **Adds** Map fields (mapper)"), text);
        assertTrue(text.contains("3. **Updates** Enrich payload (script) — script"), text);
    }

    @Test
    void leadsEveryActionWithABoldVerb() {
        String text = ChainPatchSummary.describe(
                graph(),
                patch(
                        List.of(removeNode("audit")),
                        List.of(),
                        List.of(changeProperty("enrich", "script", "return null;"))));

        assertTrue(text.contains(". **Removes** "), text);
        assertTrue(text.contains(". **Updates** "), text);
    }

    @Test
    void showsWhatAPropertyHoldsNowBesideWhatItWouldHold() {
        String text = ChainPatchSummary.describe(
                graph(), patch(List.of(), List.of(), List.of(changeProperty("enrich", "script", "return null;"))));

        assertTrue(text.contains("now:\n```\nreturn exchange;\n```"), text);
        assertTrue(text.contains("after:\n```\nreturn null;\n```"), text);
        assertTrue(text.endsWith("Apply this to the chain?"), text);
    }

    @Test
    void readsAPropertyTheElementDoesNotHaveYetAsNotSet() {
        String text = ChainPatchSummary.describe(
                graph(), patch(List.of(), List.of(), List.of(changeProperty("enrich", "language", "groovy"))));

        assertTrue(text.contains("now:\n(not set)"), text);
    }

    /** A patch may name an element the chain no longer has; the card still has to render. */
    @Test
    void fallsBackToTheIdWhenTheElementIsUnknown() {
        String text = ChainPatchSummary.describe(
                graph(), patch(List.of(removeNode("ghost")), List.of(), List.of()));

        assertTrue(text.contains("**Removes** Element ghost"), text);
    }

    @Test
    void aReplacementCardListsTheRemovalFirstAndWarnsItCannotBeUndone() {
        String text = ChainPatchSummary.describe(
                graph(),
                patch(
                        List.of(removeNode("audit"), addNode("mapper", "mapper", "Map fields")),
                        List.of(),
                        List.of()));

        assertTrue(text.indexOf("1. **Removes** Legacy audit log") < text.indexOf("2. **Adds** Map fields"), text);
        assertTrue(
                text.contains("Removing cannot be undone. To keep a way back, save a snapshot first."),
                text);
    }

    /**
     * The acceptance guard for the REMOVE migration: a replacement assembled from a captured
     * subgraph, not a hand-built patch, still gives the reader the same removal-first warning.
     */
    @Test
    void anAssembledReplacementListsTheRemovalFirstAndWarnsItCannotBeUndone() {
        ChainPlanGraph before = replacementBase();
        ChainPlanGraph assembled =
                ChainEditSubgraphAssembly.assemble(
                        before, replacementSubgraph(), replaceIntent(), permissiveCache());
        GraphPatch patch =
                CanonicalGraphDiff.between(before, assembled, "patch-1", "cip-structure-generator", null);

        String text = ChainPatchSummary.describe(before, patch);

        assertTrue(text.indexOf("**Removes** Call orders") < text.indexOf("**Adds**"), text);
        assertTrue(
                text.contains("Removing cannot be undone. To keep a way back, save a snapshot first."),
                text);
    }

    private static ChainPlanGraph replacementBase() {
        return new ChainPlanGraph(
                "1.0",
                new ChainSection("demo-chain", null),
                List.of(
                        new ChainPlanNode("trigger", "http-trigger", "Entry", null, null, List.of()),
                        new ChainPlanNode("call", "service-call", "Call orders", null, null, List.of())),
                List.of(new ChainPlanEdge("trigger->call", "trigger", "call", null)));
    }

    private static ChainEditSubgraph replacementSubgraph() {
        return new ChainEditSubgraph(
                null,
                null,
                List.of(),
                new ChainEditSubgraphBody(
                        List.of(new ChainEditSubgraphElement("mapper", "mapper", "Map fields")), List.of()));
    }

    private static ChainEditIntent replaceIntent() {
        return new ChainEditIntent(
                ChainEditAction.ADD_ELEMENTS,
                List.of("call"),
                "replace the call with a mapper",
                null,
                "mapper",
                null,
                List.of(),
                List.of(),
                ChainEditDisposition.REMOVE);
    }

    /** Every type is a permissive container, so descriptor validation never fires. */
    private static CatalogElementDescriptorCache permissiveCache() {
        CatalogElementDescriptorLoader loader = mock(CatalogElementDescriptorLoader.class);
        CatalogElementDescriptorTestSupport.stubPermissive(loader);
        return new CatalogElementDescriptorCache(loader);
    }

    /** A wrap is, at its core, an existing element moving into a new container. */
    @Test
    void namesTheMovedElementAndItsNewContainer() {
        String text = ChainPatchSummary.describe(
                graph(),
                patch(
                        List.of(
                                addNode("try", "try", "Try"),
                                moveNode("enrich", "script", "Enrich payload", "try")),
                        List.of(),
                        List.of()));

        assertTrue(text.contains("**Moves** Enrich payload (script) into Try (try)"), text);
    }

    /** The move is what makes a wrap describe the thing it actually does, so it must not be silent. */
    @Test
    void aParentTransferAloneIsNotAnEmptyPatch() {
        String text = ChainPatchSummary.describe(
                graph(), patch(List.of(moveNode("enrich", "script", "Enrich payload", "audit")), List.of(), List.of()));

        assertFalse(text.contains("The change is empty"), text);
        assertTrue(text.contains("**Moves** Enrich payload (script) into Legacy audit log (script)"), text);
    }

    /** An UPDATE that leaves the parent unchanged is an ordinary edit, not a move. */
    @Test
    void doesNotRenderAMoveWhenTheParentIsUnchanged() {
        String text = ChainPatchSummary.describe(
                graph(),
                patch(
                        List.of(moveNode("enrich", "script", "Enrich payload", null)),
                        List.of(),
                        List.of(changeProperty("enrich", "script", "return null;"))));

        assertFalse(text.contains("**Moves**"), text);
    }

    private static ChainPlanGraph graph() {
        return new ChainPlanGraph(
                "1.0",
                new ChainSection("demo-chain", null),
                List.of(
                        new ChainPlanNode("trigger", "http-trigger", "Entry", null, null, List.of()),
                        new ChainPlanNode(
                                "enrich",
                                "script",
                                "Enrich payload",
                                null,
                                null,
                                List.of(new PlanProperty("script", "return exchange;"))),
                        new ChainPlanNode("audit", "script", "Legacy audit log", null, null, List.of())),
                List.of(
                        new ChainPlanEdge("trigger->enrich", "trigger", "enrich", null),
                        new ChainPlanEdge("enrich->audit", "enrich", "audit", null)));
    }

    private static GraphPatch patch(
            List<NodePatch> nodePatches, List<EdgePatch> edgePatches, List<PropertyPatch> propertyPatches) {
        return new GraphPatch(
                "patch-1",
                "cip-compare-and-patch",
                nodePatches,
                edgePatches,
                propertyPatches,
                List.of(),
                List.of(),
                null);
    }

    private static NodePatch removeNode(String nodeId) {
        return new NodePatch(GraphPatchOperation.REMOVE, null, nodeId);
    }

    private static NodePatch addNode(String nodeId, String type, String label) {
        return new NodePatch(
                GraphPatchOperation.ADD,
                new ChainPlanNode(nodeId, type, label, null, null, List.of()),
                null);
    }

    private static NodePatch moveNode(String nodeId, String type, String label, String newParentNodeId) {
        return new NodePatch(
                GraphPatchOperation.UPDATE,
                new ChainPlanNode(nodeId, type, label, newParentNodeId, null, List.of()),
                nodeId);
    }

    private static EdgePatch removeEdge(String edgeId) {
        return new EdgePatch(GraphPatchOperation.REMOVE, null, edgeId);
    }

    private static PropertyPatch changeProperty(String nodeId, String key, String value) {
        return new PropertyPatch(GraphPatchOperation.UPDATE, nodeId, new PlanProperty(key, value));
    }
}

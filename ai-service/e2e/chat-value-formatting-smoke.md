# Chat value formatting smoke test

Use the campaign's configured model and synthetic OpenAPI fixtures. Keep the conversation IDs,
served build, screenshots, decision bindings, and catalog readbacks in the campaign report.

1. Start a new chat and upload two distinct OpenAPI specifications. Request a chain that uses
   one operation from each service and ends with a named Script element.
2. Verify that both specification display names appear in native bold text beside their independent
   Internal/External choices. Select the requested type for each file and import through the card.
   The submitted command must retain each full storage key and its corresponding selection.
3. Complete the normal approval gates and materialize the chain. Require its exact name in bold,
   its real catalog ID as inline code, and an Open graph link to that chain.
   Read back both Service Call bindings and confirm that they point to the intended separate services.
4. Open the created chain and ask Rocky to change one property of the named Script element.
   Require the proposed patch to show the element name in bold and its type and property key as inline code.
   Current and proposed script bodies must remain separate code blocks with their literal values.
   Require one framed block and one Copy button per fragment, including blocks with no language tag.
   Copy the proposed fragment and verify its complete text. Long lines must scroll inside the block.
5. Reload the page and reopen the same edit conversation. Compare the decision ID, artifact hash,
   revision, actions, and formatted question with the original card.
6. Enter feedback in the card's comment field and click Request changes. Require a revised proposal
   that preserves the earlier edit and includes the feedback. The catalog must remain unchanged.
   Request another revision that removes a line from the proposed script. Reload the conversation
   and verify that the latest proposal remains actionable. With feedback entered, the primary action
   must say Review changes and request a new proposal instead of applying the previous one.
7. Clear the comment and apply through the card. Read the chain back and verify the final property change, unchanged
   element identities, service bindings, and execution edges. Do not use Continue repair to obtain a passing verdict.

This check verifies presentation, imports, materialization, and editing. It does not claim that
synthetic external endpoints were invoked or that the chain was deployed.

## Focused regression checks

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
  mvn -o -f ai-service/pom.xml \
  -Dtest=MarkdownTextTest,ChainMaterializedSummaryTest,ChainPatchSummaryTest,MaterializationCapabilityTest,ProductPipelineImplementationGateTest,UploadedSpecsApprovalHandlerTest \
  test
```

From the UI directory:

```bash
npm test -- --runInBand --testPathPattern=tests/components/ai/AiDecisionCard.test.tsx
```

The checks cover literal name escaping, blank-name ID fallbacks, patch action ordering and removal warnings,
unchanged script values, and two independent import selections with literal display names.

For comment revisions, run `ChainPatchScenarioTest`, `ChatExecutionServiceTest`, and `ChatDecisionServiceTest`.
They cover routing by artifact type, full proposal context, stale feedback, empty feedback, and failed revisions.

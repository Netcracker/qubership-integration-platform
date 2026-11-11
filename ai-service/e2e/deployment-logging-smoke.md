# Deployment and logging smoke test

Run this browser check once per live chain campaign on a retained chain with an HTTP trigger.
Use the campaign's configured model. Deployment is a separate verdict from materialization.
Keep the conversation ID, chain ID, served version, screenshots, and catalog readbacks in the campaign report.

1. Open the materialized chain in the UI and send `Deploy this chain` in its chat.
   Do not request a logging question in the prompt. For a live deployment, confirm the offered redeploy first.
2. Verify that the chat automatically asks for the session logging level and offers Off, Error, Info, and Debug.
   Before selecting a level, verify that no deployment or redeploy has occurred.
3. Read `GET /api/v1/chat/{conversationId}/decision`. Require HTTP 200, artifact type `SESSION_LOGGING`,
   all four `session-logging-*` actions, and the same card binding as the streamed question.
4. Close and reopen the chat, then reload the page and reopen the same conversation.
   Require the same visible question and four enabled buttons. Repeat the decision readback and compare its ID and hash.
5. Select Info once. Require a visible deployment result and verify in the catalog that session logging is `INFO`.
   Verify that log logging level, payload selection, masking, and DPT event settings retain their prior values.
6. Require exactly one deployment on the selected domain with engine state `DEPLOYED`.
   If deployment is still `PROCESSING`, require a visible Check status card that survives chat reopen.
   If deployment fails, require the real failure to remain available and a visible recovery card.
7. Reload the page and reopen the chat again. Require the logging question, selected Info answer,
   and deployment result to remain in the transcript. A completed deployment must not reopen the logging choice.

Repeat the deployment command in a new chat in a second language. Require questions, action labels,
and deployment results in that language. Chain names, IDs, domains, snapshot names, and logging enum values
must remain unchanged. Verify bold names, domains, snapshots, and statuses, with IDs rendered as inline code.
The translated action labels must still submit the original action IDs. Repeat chat reload before selecting a level.

Do not use Continue repair, start a replacement chat, or change chain parameters to obtain a passing verdict.
Record infrastructure and runtime failures separately from missing cards or lost chat history.
This test verifies deployment and chat state; it does not claim that an external API was invoked successfully.

## Automated regression coverage

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
  mvn -o -f ai-service/pom.xml \
  -Dtest=DeploymentMessagesTest,MarkdownTextTest,ChatDecisionServiceTest,DeployChainScenarioTest,PendingRedeployStoreTest,ChatExecutionServiceTest,ConversationTurnResetTest \
  test
```

The tests cover automatic logging questions, projection after the scenario stream, duplicate SSE cards,
all deployment card kinds, operation replacement, stale answers, logging write timeout, status read timeout,
completed status refresh, and conversation reset.
Localization tests cover dictionary caching, exact placeholder preservation, invalid translation fallback,
language recovery from archived prose, Markdown escaping, and translated action labels in SSE and restored decisions.

const assert = require("node:assert/strict");
const { test } = require("@jest/globals");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const repo = path.resolve(__dirname, "../../../..");
const ts = require("typescript");
const assistantPath = path.join(repo, "ui/src/components/ai/AiAssistant.tsx");
const helperPath = path.join(repo, "ui/src/components/ai/chatDecisionUtils.ts");
const assistantSource = fs.readFileSync(assistantPath, "utf8");
const helperSource = fs.readFileSync(helperPath, "utf8");

function extractCallback(name) {
  const sourceFile = ts.createSourceFile(
    assistantPath,
    assistantSource,
    ts.ScriptTarget.Latest,
    true,
    ts.ScriptKind.TSX,
  );
  let callback;
  function visit(node) {
    if (
      ts.isVariableDeclaration(node) &&
      node.name.getText(sourceFile) === name
    ) {
      assert(
        ts.isCallExpression(node.initializer),
        `${name} must remain a hook call`,
      );
      callback = node.initializer.arguments[0].getText(sourceFile);
    }
    ts.forEachChild(node, visit);
  }
  visit(sourceFile);
  assert(callback, `Missing actual callback: ${name}`);
  return callback;
}

function transpile(source) {
  return ts.transpileModule(source, {
    compilerOptions: {
      target: ts.ScriptTarget.ES2021,
      module: ts.ModuleKind.CommonJS,
    },
  }).outputText;
}

const helpersModule = { exports: {} };
vm.runInNewContext(transpile(helperSource), {
  exports: helpersModule.exports,
  module: helpersModule,
});
const helpers = helpersModule.exports;
const actualCallback = extractCallback("reconcileOpenDecision");
const actualAnswerCallback = extractCallback("handleDecisionAnswer");
const actualStreamingCallback = extractCallback("runStreamingChat");
const sendSourceFile = ts.createSourceFile(
  "send.ts",
  `const send = ${extractCallback("sendToProvider")};`,
  ts.ScriptTarget.Latest,
  true,
);
const actualSend =
  sendSourceFile.statements[0].declarationList.declarations[0].initializer;
const sendStartBookkeeping = actualSend.body.statements
  .filter((statement) => {
    if (!ts.isExpressionStatement(statement)) return false;
    const expression = statement.expression;
    return (
      (ts.isPrefixUnaryExpression(expression) &&
        expression.operator === ts.SyntaxKind.PlusPlusToken) ||
      (ts.isBinaryExpression(expression) &&
        expression.left.getText(sendSourceFile) ===
          "sendInProgressRef.current" &&
        expression.right.kind === ts.SyntaxKind.TrueKeyword)
    );
  })
  .map((statement) => statement.getText(sendSourceFile))
  .join("\n");
assert(
  sendStartBookkeeping.includes("sendInProgressRef.current = true;"),
  "Missing actual send-start bookkeeping",
);

function deferred() {
  let resolve;
  const promise = new Promise((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

test.each([false, true])(
  "should persist completion across suspended frames with a gate: %s",
  async (withGate) => {
    const loadHelper = (file) => {
      const module = { exports: {} };
      vm.runInNewContext(transpile(fs.readFileSync(file, "utf8")), {
        exports: module.exports,
        module,
        setTimeout,
        clearTimeout,
        requestAnimationFrame: () => 1,
        cancelAnimationFrame: () => {},
      });
      return module.exports;
    };
    const textHelpers = loadHelper(
      path.join(repo, "ui/src/components/ai/chatMessageUtils.ts"),
    );
    const revealHelpers = loadHelper(
      path.join(repo, "ui/src/components/ai/streamReveal.ts"),
    );
    const completion = `${"Chain configuration saved. ".repeat(20)}[Open graph](/chains/chain-1/graph)`;
    const beforeGate = "Requirements ready for review. ".repeat(10);
    const session = { messages: [] };
    let streaming = false;
    let completed = false;
    const context = {
      ...textHelpers,
      ...revealHelpers,
      ...helpers,
      performance: { now: () => 100 },
      console,
      setIsStreaming: (value) => {
        streaming = value;
      },
      activityStore: {
        reset: () => {},
        getRows: () => [],
        applyStep: () => {},
      },
      sessionStore: {
        updateSessionMessages: (_id, messages) => {
          session.messages = messages;
        },
      },
      refreshSessions: () => {},
      throttledRefreshSessions: () => {},
      scrollToBottom: () => {},
      chainContext: undefined,
      abortControllerRef: { current: new AbortController() },
      attachActivityToLastAssistant: (messages) => messages,
      handleResponseComplete: (_id, response) => {
        session.messages = response.finalMessages;
        completed = true;
      },
    };
    const run = vm.runInNewContext(
      transpile(`const callback = ${actualStreamingCallback}; callback;`),
      context,
    );
    const turn = run(
      {
        streamChat: async (_payload, emit) => {
          if (withGate) {
            emit({ type: "delta", contentDelta: beforeGate });
            emit({
              type: "decision",
              decision: {
                id: "review:1",
                kind: "approve",
                question: "Approve brief?",
                revision: 1,
                actions: ["approve"],
              },
            });
          }
          emit({ type: "delta", contentDelta: completion });
          // Let the real reveal loop reach a suspended frame before the terminal event.
          await Promise.resolve();
          await Promise.resolve();
          emit({ type: "done", conversationId: "conversation-1" });
        },
      },
      {},
      "session-1",
      [{ role: "user", content: "Create the chain" }],
      0,
      "conversation-1",
    );
    await Promise.race([
      turn,
      new Promise((resolve) => setTimeout(resolve, 100)),
    ]);
    assert.equal(
      completed,
      true,
      "Terminal persistence must not depend on a browser paint",
    );
    assert.equal(streaming, false);
    assert(session.messages.some((message) => message.content === completion));
    if (withGate) {
      const gateIndex = session.messages.findIndex(
        (message) => message.decision?.id === "review:1",
      );
      assert(gateIndex > 0);
      assert.equal(session.messages[gateIndex].content, beforeGate);
      assert.equal(session.messages[gateIndex + 1].content, completion);
    }
  },
);

function setup() {
  const offer = {
    id: "clarify:19",
    kind: "clarify",
    question: "Continue repair with a finite allowance?",
    revision: 19,
    actions: ["continue-repair", "stop-with-report"],
    recovery: {
      category: "repair-allowance-required",
      title: "Additional repair allowance needed",
      summary:
        "Two targeted corrections and two clean regenerations are offered.",
      preservedWork: "Approved work is saved.",
      technicalDetails: "2 targeted and 2 clean attempts spent.",
    },
  };
  const session = {
    id: "session-1",
    conversationId: "conversation-1",
    messages: [
      { role: "user", content: "Create a chain" },
      { role: "assistant", content: "", decision: offer },
    ],
  };
  const fetches = [];
  const sendInProgressRef = { current: false };
  const sendCompletion = deferred();
  const submissions = [];
  const context = {
    console,
    sendInProgressRef,
    decisionReconciliationGenerationRef: { current: 0 },
    currentSessionId: session.id,
    EXPORT_CHAINS_ACTION: "export-chains",
    exportFromCard: () => assert.fail("Export is outside this probe"),
    resumeFollowing: () => {},
    markDecisionAnswered: helpers.markDecisionAnswered,
    sendToProvider: async (...args) => {
      submissions.push(args);
      vm.runInNewContext(sendStartBookkeeping, context);
      await sendCompletion.promise;
      sendInProgressRef.current = false;
    },
    fetchOpenDecision: () => {
      assert(fetches.length > 0, "No uncontrolled fetch is permitted");
      return fetches.shift();
    },
    sessionStore: {
      getSession: (id) => (id === session.id ? session : undefined),
      updateSessionMessages: (id, messages) => {
        assert.equal(id, session.id);
        session.messages = messages;
      },
    },
    refreshSessions: () => {},
    reconcileDecisionMessages: helpers.reconcileDecisionMessages,
  };
  const reconcile = vm.runInNewContext(
    transpile(`const callback = ${actualCallback}; callback;`),
    context,
  );
  const answer = vm.runInNewContext(
    transpile(
      `const answerCallback = ${actualAnswerCallback}; answerCallback;`,
    ),
    context,
  );
  const pending = () =>
    session.messages.filter(
      (message) =>
        message.decision && message.decision.answeredAction === undefined,
    );
  return {
    offer,
    session,
    fetches,
    sendInProgressRef,
    sendCompletion,
    submissions,
    answer,
    reconcile,
    pending,
  };
}

test("ignores a stale decision response after a completed repair continuation", async () => {
  const inFlight = setup();
  const responseDuringTurn = deferred();
  inFlight.fetches.push(responseDuringTurn.promise);
  const guarded = inFlight.reconcile(
    inFlight.session.conversationId,
    inFlight.session.id,
  );
  const stillRunningSubmission = inFlight.answer(
    inFlight.offer,
    "continue-repair",
    "",
  );
  responseDuringTurn.resolve(inFlight.offer);
  await guarded;
  assert.equal(
    inFlight.pending().length,
    0,
    "In-flight response must not restore the answered gate",
  );
  inFlight.sendCompletion.resolve();
  await stillRunningSubmission;

  const beforeNextFetch = setup();
  const heldBeforeCommand = deferred();
  beforeNextFetch.fetches.push(heldBeforeCommand.promise);
  const heldReconciliation = beforeNextFetch.reconcile(
    beforeNextFetch.session.conversationId,
    beforeNextFetch.session.id,
  );
  const completedSubmission = beforeNextFetch.answer(
    beforeNextFetch.offer,
    "continue-repair",
    "",
  );
  beforeNextFetch.sendCompletion.resolve();
  await completedSubmission;
  heldBeforeCommand.resolve(beforeNextFetch.offer);
  await heldReconciliation;
  assert.equal(
    beforeNextFetch.pending().length,
    0,
    "A response made stale by a completed send must be ignored before the next fetch",
  );

  const crossing = setup();
  const oldResponse = deferred();
  crossing.fetches.push(oldResponse.promise);
  const oldReconciliation = crossing.reconcile(
    crossing.session.conversationId,
    crossing.session.id,
  );

  const submission = crossing.answer(crossing.offer, "continue-repair", "");
  assert.equal(crossing.submissions.length, 1);
  assert.equal(crossing.submissions[0][0], crossing.session.id);
  assert.equal(crossing.submissions[0][6].action, "continue-repair");
  assert.equal(crossing.submissions[0][6].revision, 19);
  assert.equal(crossing.session.conversationId, "conversation-1");
  assert.equal(
    crossing.session.messages[1].decision.answeredAction,
    "continue-repair",
  );
  crossing.sendCompletion.resolve();
  await submission;

  crossing.fetches.push(Promise.resolve(null));
  await crossing.reconcile(
    crossing.session.conversationId,
    crossing.session.id,
  );
  assert.equal(
    crossing.pending().length,
    0,
    "The newer no-gate response must leave no pending decision",
  );

  oldResponse.resolve(crossing.offer);
  await oldReconciliation;
  assert.equal(
    crossing.pending().length,
    0,
    "A stale pre-command response must not resurrect an actionable repair allowance card",
  );
});

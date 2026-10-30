"use strict";
const $ = (id) => document.getElementById(id);
const terminal = new Set(["PASS", "FAIL", "ERROR", "BLOCKED", "CANCELLED"]);
let catalog,
  lastDetail = "";
function node(tag, text, className) {
  const item = document.createElement(tag);
  if (text !== undefined) item.textContent = text;
  if (className) item.className = className;
  return item;
}
function badge(status) {
  return node("span", status, "badge " + status);
}
async function api(path, body) {
  const response = await fetch(
    path,
    body === undefined
      ? {}
      : {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(body),
        },
  );
  const value = await response.json();
  if (!response.ok) throw new Error(value.error || response.statusText);
  return value;
}
function link(text, href) {
  const item = node("a", text);
  item.href = href;
  return item;
}
function artifact(run, text, path) {
  return link(
    text,
    "/api/runs/" +
      encodeURIComponent(run.id) +
      "/artifacts/" +
      path.split("/").map(encodeURIComponent).join("/"),
  );
}
function describeProfile() {
  const profile = catalog.profiles.find((p) => p.id === $("profile").value);
  $("profile-detail").textContent = profile.available
    ? profile.suites
        .map((id) => {
          const suite = catalog.suites.find((s) => s.id === id);
          return id + (suite.usesLlm ? " (LLM)" : " (no LLM)");
        })
        .join(" · ")
    : profile.reason;
}
function diagnosis(parent, title, value) {
  parent.append(
    node("h3", title + " · " + value.status),
    node("p", value.summary || "No diagnosis saved."),
  );
}
function displayRun(run) {
  const root = $("detail");
  root.replaceChildren(node("h2", run.profile), badge(run.status));
  const facts = node("dl");
  const values = {
    "Run ID": run.id,
    Created: run.createdAt,
    "Service commit": run.service.commit,
    "Working changes": run.service.diffSha256 || "unavailable",
    "Skills package": run.skillsPackage?.version || "not installed",
    Model: run.settings.model || "unspecified",
    Environment: run.settings.base_url,
    "Parent run": run.parentRunId || "—",
  };
  for (const [key, value] of Object.entries(values))
    facts.append(node("dt", key), node("dd", value));
  root.append(facts);
  if (run.reason) root.append(node("p", run.reason));
  const actions = node("div", undefined, "actions");
  actions.append(
    link("Download report", "/api/runs/" + run.id + "/report.md"),
    artifact(run, "Run manifest", "run.json"),
  );
  if (!terminal.has(run.status)) {
    const cancel = node("button", "Cancel run");
    cancel.onclick = () =>
      act(() => api("/api/runs/" + run.id + "/cancel", {}));
    actions.append(cancel);
  } else {
    const retry = node("button", "Run again");
    retry.onclick = () =>
      act(async () => {
        const next = await api("/api/runs", {
          profile: run.profile,
          suites: run.tasks.map((task) => task.suite.id),
          settings: run.settings,
          allowLlm: run.allowLlm,
          parentRunId: run.id,
        });
        location.hash = next.id;
      });
    actions.append(retry);
  }
  root.append(actions);
  const table = node("table");
  const head = node("tr");
  for (const text of ["Suite", "Status", "Evidence boundary", "Artifacts"])
    head.append(node("th", text));
  table.append(head);
  for (const task of run.tasks) {
    const row = node("tr"),
      status = node("td");
    status.append(badge(task.status));
    row.append(
      node("td", task.suite.id),
      status,
      node("td", task.verifiedBoundary || task.reason || "Awaiting results"),
    );
    const files = node("td");
    if (task.log) files.append(artifact(run, "Log", task.log));
    if (task.evidence?.length === 1)
      files.append(
        node("br"),
        artifact(run, "Native report", task.directory + "/" + task.evidence[0]),
      );
    if (task.artifacts?.length) {
      const all = node("details");
      all.append(node("summary", "All artifacts · " + task.artifacts.length));
      for (const path of task.artifacts)
        all.append(
          artifact(run, path.split("/").slice(2).join("/"), path),
          node("br"),
        );
      files.append(all);
    }
    row.append(files);
    table.append(row);
  }
  root.append(table);
  const failures = run.tasks.flatMap((t) => t.failures || []);
  root.append(node("h2", "Failure episodes · " + failures.length));
  if (!failures.length)
    root.append(
      node(
        "p",
        terminal.has(run.status)
          ? "No failure episodes were reported."
          : "Failures appear here as suites finish.",
        "muted",
      ),
    );
  for (const episode of failures) {
    const card = node("article", undefined, "episode");
    card.append(
      node("h3", episode.id + " · " + episode.caseId),
      node("p", episode.category),
    );
    const columns = node("div", undefined, "columns"),
      original = node("div"),
      recovery = node("div");
    original.append(
      node("h3", "Original failure"),
      node("pre", episode.error.message),
    );
    diagnosis(original, "Root cause", episode.diagnosis);
    recovery.append(node("h3", "Recovery · " + episode.recovery.status));
    diagnosis(recovery, "Why recovery failed", episode.recovery.diagnosis);
    recovery.append(
      node("pre", JSON.stringify(episode.recovery.attempts, null, 2)),
    );
    columns.append(original, recovery);
    card.append(columns);
    const details = node("details");
    details.append(
      node("summary", "Episode evidence and diagnosis history"),
      node("pre", JSON.stringify(episode, null, 2)),
    );
    card.append(details);
    root.append(card);
  }
  const active = run.tasks.find((t) => t.status === "RUNNING" && t.log);
  if (active) {
    const details = node("details");
    details.open = true;
    details.append(node("summary", "Current runner output"));
    const pre = node("pre", "Loading…", "log");
    details.append(pre);
    root.append(details);
    fetch(artifact(run, "", active.log).href)
      .then((r) => (r.ok ? r.text() : "Waiting for the runner log…"))
      .then((text) => (pre.textContent = text.slice(-18000)))
      .catch(() => {});
  }
}
async function refresh() {
  const runs = await api("/api/runs");
  const history = $("history");
  history.replaceChildren();
  if (!runs.length) history.append(node("p", "No runs yet.", "muted"));
  for (const run of runs) {
    const item = link("", "#" + run.id);
    item.append(
      badge(run.status),
      node("div", run.profile),
      node("small", run.id),
    );
    history.append(item);
  }
  const id = location.hash.slice(1) || runs[0]?.id;
  if (id) {
    const run = await api("/api/runs/" + encodeURIComponent(id));
    const serialized = JSON.stringify(run);
    if (serialized !== lastDetail || !terminal.has(run.status)) {
      lastDetail = serialized;
      displayRun(run);
    }
  }
}
async function act(action) {
  try {
    $("error").textContent = "";
    await action();
    await refresh();
  } catch (error) {
    $("error").textContent = error.message;
  }
}
$("launch").addEventListener("submit", (event) => {
  event.preventDefault();
  act(async () => {
    $("start").disabled = true;
    try {
      const run = await api("/api/runs", {
        profile: $("profile").value,
        settings: {
          base_url: $("base-url").value,
          model: $("model").value,
          image: $("image").value,
        },
        allowLlm: $("allow-llm").checked,
      });
      location.hash = run.id;
    } finally {
      $("start").disabled = false;
    }
  });
});
window.addEventListener("hashchange", () => act(refresh));
act(async () => {
  catalog = await api("/api/catalog");
  for (const profile of catalog.profiles) {
    const option = node(
      "option",
      profile.id + (profile.available ? "" : " · unavailable"),
    );
    option.value = profile.id;
    $("profile").append(option);
  }
  describeProfile();
  $("profile").onchange = describeProfile;
});
setInterval(() => act(refresh), 2000);

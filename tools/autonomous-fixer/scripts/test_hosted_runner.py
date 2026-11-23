import hashlib
import json
import os
import shlex
import subprocess
import sys
import tarfile
import tempfile
import unittest
from pathlib import Path
from urllib.request import Request

import hosted_issue

SCRIPTS = Path(__file__).resolve().parent
REPO = "Netcracker/qubership-integration-platform"
IMAGE = (
    "https://github.com/user-attachments/assets/3ade1d33-b370-43be-adac-b98b2c276ce1"
)


def admitted_issue(body=None, sha="a" * 40, legacy=False):
    title = "Navigation keeps the selected folder"
    issue = {
        "number": 878,
        "state": "open",
        "title": title,
        "body": body,
        "labels": [{"name": "ready-for-agent"}],
    }
    requirements = f"{title}\n\n{body or ''}"
    contract = {"baseSha": sha, "scopePaths": ["ui"]}
    if legacy:
        contract["requirements"] = requirements
    else:
        contract["requirementsSha256"] = hashlib.sha256(
            requirements.encode("utf-8")
        ).hexdigest()
    checkpoint = {"issueNumber": 878, "taskId": "qip-878", "contract": contract}
    return checkpoint, issue


class HostedIssueTest(unittest.TestCase):
    def test_captures_only_github_attachments_and_preserves_the_issue(self):
        body = f"![Preview]({IMAGE})\n{IMAGE}\n[local](https://127.0.0.1/a.png)"
        checkpoint, issue = admitted_issue(body)
        calls = []

        def fetch(url, repository, token):
            calls.append((url, repository, token))
            return "image/png", b"example PNG data"

        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary) / "assignment"
            hosted_issue.snapshot(
                checkpoint, issue, REPO, directory, "read-token", fetch
            )
            self.assertEqual(calls, [(IMAGE, REPO, "read-token")])
            self.assertIn(
                f"{issue['title']}\n\n{body}", (directory / "task.md").read_text()
            )
            self.assertEqual(
                (directory / "attachment-01.png").read_bytes(), b"example PNG data"
            )
            self.assertNotIn("read-token", (directory / "attachments.json").read_text())

    def test_rejects_stale_issue_before_fetching(self):
        checkpoint, issue = admitted_issue(IMAGE)
        issue["body"] += "\nUpdated after admission."
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary) / "assignment"
            with self.assertRaisesRegex(ValueError, "changed since admission"):
                hosted_issue.snapshot(
                    checkpoint, issue, REPO, directory, None, self.fail
                )
            self.assertFalse(directory.exists())

    def test_captures_large_unicode_requirements_from_the_issue(self):
        body = "Последовательность действий.\r\n" * 1800
        checkpoint, issue = admitted_issue(body)
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary) / "assignment"
            hosted_issue.snapshot(checkpoint, issue, REPO, directory, None)
            self.assertIn(body.encode("utf-8"), (directory / "task.md").read_bytes())

    def test_reads_a_legacy_checkpoint(self):
        checkpoint, issue = admitted_issue("Previous work", legacy=True)
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary) / "assignment"
            hosted_issue.snapshot(checkpoint, issue, REPO, directory, None)
            self.assertIn(
                checkpoint["contract"]["requirements"],
                (directory / "task.md").read_text(),
            )

    def test_binds_the_authorized_answers_to_the_assignment(self):
        checkpoint, issue = admitted_issue()
        answer = {
            "questionId": "qip-878/question/1",
            "questionVersion": 1,
            "contractVersion": 2,
            "question": "Where does Back return?",
            "answer": "Return to the last folder.\nKeep its selection.",
            "actor": "issue-owner",
            "source": "comment:20",
        }
        checkpoint.update(answers=[answer], contractVersion=2)
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary) / "assignment"
            hosted_issue.snapshot(checkpoint, issue, REPO, directory, None)
            self.assertEqual(
                json.loads((directory / "answers.json").read_text()), [answer]
            )
            self.assertIn(
                "> Return to the last folder.\n> Keep its selection.",
                (directory / "task.md").read_text(),
            )
            checkpoint["contractVersion"] += 1
            with self.assertRaisesRegex(ValueError, "different contract version"):
                hosted_issue.snapshot(checkpoint, issue, REPO, directory, None)

    def test_assignment_requires_the_admitted_revision_feedback(self):
        checkpoint, issue = admitted_issue("Original acceptance criteria.")
        pull = f"https://github.com/{REPO}/pull/900"
        revision = {
            "head": checkpoint["contract"]["baseSha"],
            "issue": issue["number"],
            "commentId": 12,
            "pullNumber": 900,
            "originRun": {"runId": 52, "runAttempt": 1},
            "instruction": "Keep the empty state visible.",
            "feedback": [
                {
                    "url": f"{pull}#discussion_r4",
                    "path": "ui/view.ts",
                    "line": 42,
                    "body": "Preserve the selected row.",
                }
            ],
        }
        revision_data = json.dumps(revision).encode()
        checkpoint["revisionSha256"] = hashlib.sha256(revision_data).hexdigest()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            checkpoint_path = root / "checkpoint.json"
            issue_path = root / "issue.json"
            revision_path = root / "revision.json"
            assignment = root / "assignment"
            checkpoint_path.write_text(json.dumps(checkpoint))
            issue_path.write_text(json.dumps(issue))
            revision_path.write_bytes(revision_data + b"\n")
            command = [
                sys.executable,
                SCRIPTS / "hosted_issue.py",
                checkpoint_path,
                issue_path,
                assignment,
            ]
            env = {**os.environ, "GITHUB_REPOSITORY": REPO}
            missing_revision = subprocess.run(
                command, capture_output=True, env=env, check=False
            )
            self.assertEqual(missing_revision.returncode, 1)
            self.assertIn(b"requires its selected feedback", missing_revision.stderr)
            subprocess.run([*command, "--revision", revision_path], env=env, check=True)
            task = (assignment / "task.md").read_text()
            self.assertIn("Original acceptance criteria.", task)
            self.assertIn(f"{pull}#issuecomment-12", task)
            self.assertIn(f"{pull}#discussion_r4", task)
            self.assertIn("`ui/view.ts:42`", task)
            self.assertIn("/actions/runs/52/attempts/1", task)
            self.assertIn("> Keep the empty state visible.", task)
            self.assertIn("> Preserve the selected row.", task)
            revision_path.write_bytes(
                revision_data.replace(b"selected row", b"another row")
            )
            command[-1] = root / "tampered"
            tampered = subprocess.run(
                [*command, "--revision", revision_path],
                capture_output=True,
                env=env,
                check=False,
            )
            self.assertEqual(tampered.returncode, 1)
            self.assertIn(b"differs from the admitted revision", tampered.stderr)
            self.assertFalse(command[-1].exists())

    def test_renders_large_automatic_review_and_ci_evidence(self):
        checkpoint, issue = admitted_issue()
        pull = f"https://github.com/{REPO}/pull/900"
        job_url = f"https://github.com/{REPO}/actions/runs/84/job/21"
        feedback = []
        for i in range(13):
            body = f"Preserve behavior {i + 1}. ".ljust(4000, "x")
            feedback.append(
                {
                    "url": f"{pull}#discussion_r{i + 1}",
                    "path": "ui/view.ts",
                    "line": 42 + i,
                    "body": body,
                }
            )
        feedback.append(
            {
                "kind": "ci",
                "url": job_url,
                "body": "Compile UI.\nTypeScript reported TS1234 at ui/view.ts:42:8.",
            }
        )
        revision = {
            "automatic": True,
            "head": checkpoint["contract"]["baseSha"],
            "issue": issue["number"],
            "commentId": 12,
            "pullNumber": 900,
            "originRun": {"runId": 52, "runAttempt": 1},
            "feedback": feedback,
        }
        revision_data = json.dumps(revision).encode()
        checkpoint["revisionSha256"] = hashlib.sha256(revision_data).hexdigest()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            checkpoint_path = root / "checkpoint.json"
            issue_path = root / "issue.json"
            revision_path = root / "revision.json"
            assignment = root / "assignment"
            checkpoint_path.write_text(json.dumps(checkpoint))
            issue_path.write_text(json.dumps(issue))
            revision_path.write_bytes(revision_data + b"\n")
            subprocess.run(
                [
                    sys.executable,
                    SCRIPTS / "hosted_issue.py",
                    checkpoint_path,
                    issue_path,
                    assignment,
                    "--revision",
                    revision_path,
                ],
                env={**os.environ, "GITHUB_REPOSITORY": REPO},
                check=True,
            )
            task = (assignment / "task.md").read_text()
        self.assertIn(f"[Automatic request]({pull}#issuecomment-12)", task)
        for item in feedback[:-1]:
            self.assertIn(
                f"[Discussion]({item['url']}) (`ui/view.ts:{item['line']}`)\n\n> {item['body']}\n",
                task,
            )
        self.assertIn(f"[CI check]({job_url})", task)
        self.assertIn(
            "> Compile UI.\n> TypeScript reported TS1234 at ui/view.ts:42:8.", task
        )

    def test_attachments_leave_a_receipt_on_download_failure(self):
        checkpoint, issue = admitted_issue(IMAGE)

        def unavailable(*args):
            raise OSError("network down")

        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary) / "assignment"
            with self.assertRaisesRegex(ValueError, "attachments are unavailable"):
                hosted_issue.snapshot(
                    checkpoint, issue, REPO, directory, None, unavailable
                )
            self.assertEqual(
                json.loads((directory / "attachments.json").read_text()),
                [{"index": 1, "status": "unavailable"}],
            )
            self.assertFalse((directory / "task.md").exists())

    def test_redirects_strip_authorization_and_reject_unapproved_hosts(self):
        handler = hosted_issue.AttachmentRedirect(REPO)
        request = Request(IMAGE, headers={"Authorization": "Bearer private"})
        redirected = handler.redirect_request(
            request, None, 302, "", {}, "https://objects.githubusercontent.com/image"
        )
        self.assertIsNone(redirected.get_header("Authorization"))
        with self.assertRaisesRegex(ValueError, "leaves GitHub's asset hosts"):
            handler.redirect_request(
                request, None, 302, "", {}, "https://github.com@127.0.0.1/x"
            )


class HostedRunnerTest(unittest.TestCase):
    def test_prepares_and_launches_with_separate_credentials(self):
        self.run_fixture("author-reported-fixed", 0)

    def test_releases_preview_when_the_controller_needs_an_owner(self):
        self.run_fixture("needs-human", 1)

    def test_revision_prepares_with_an_isolated_environment_and_public_maven_settings(
        self,
    ):
        self.run_fixture("author-reported-fixed", 0, revision=True)

    def run_fixture(self, status, exit_code, revision=False):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            trusted = root / "trusted"
            workspace = root / "candidate"
            hosted = root / "hosted"
            assignment = hosted / "assignment"
            tools = root / "bin"
            downloads = root / "downloads"
            for path in (trusted, workspace, hosted, tools, downloads):
                path.mkdir()

            def file(path, contents, executable=False):
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(contents, encoding="utf-8")
                if executable:
                    path.chmod(0o755)

            def cli(name, contents):
                file(
                    tools / name,
                    "#!/usr/bin/env bash\nset -euo pipefail\n" + contents,
                    executable=True,
                )

            def execute(*argv, cwd=None, expected=0):
                result = subprocess.run(
                    argv,
                    check=False,
                    cwd=cwd,
                    env=environment,
                    capture_output=True,
                    text=True,
                    timeout=35,
                )
                self.assertEqual(
                    result.returncode, expected, result.stdout + result.stderr
                )
                return result.stdout.strip()

            file(downloads / "kind", "#!/bin/sh\nexit 0\n")
            file(downloads / "istioctl", "#!/bin/sh\nexit 0\n", executable=True)
            with tarfile.open(downloads / "istioctl.tar.gz", "w:gz") as archive:
                archive.add(downloads / "istioctl", arcname="istioctl")
            pins = {
                name: hashlib.sha256((downloads / name).read_bytes()).hexdigest()
                for name in ("kind", "istioctl.tar.gz")
            }
            file(
                trusted / ".github/workflows/_e2e-kind.yaml",
                f"""
          KIND_VERSION: v0.33.0
          KIND_SHA256: {pins['kind']}
          ISTIO_VERSION: 1.31.1
          ISTIOCTL_SHA256: {pins['istioctl.tar.gz']}
""",
            )
            file(
                trusted / ".github/maven-settings.xml",
                "<settings><servers>trusted-test-settings</servers></settings>\n",
            )
            file(
                trusted / "e2e/k8s/bootstrap-cluster.sh",
                'set -eu\ntest -z "${MAVEN_GITHUB_PASSWORD:-}"\nprintf \'fixture\' > "$KUBECONFIG"\n',
            )
            provisioner = (
                trusted / "tools/autonomous-fixer/scripts/hosted_provision.mjs"
            )
            file(provisioner, (SCRIPTS / "hosted_provision.mjs").read_text())
            cli(
                "curl",
                f'while [[ $1 != -o ]]; do shift; done\ncp {shlex.quote(str(downloads))}/"$2" "$2"\n',
            )
            npm_check = 'test -z "${MAVEN_GITHUB_PASSWORD:-}"\n'
            if revision:
                npm_check += (
                    'test -z "${GITHUB_TOKEN:-}${GH_TOKEN:-}${NODE_AUTH_TOKEN:-}'
                    '${AGENT_PUBLICATION_TOKEN:-}${AGENT_VARIABLES_READ_TOKEN:-}"\n'
                    'test -z "${NPM_CONFIG_USERCONFIG:-}${MAVEN_ARGS:-}${MAVEN_OPTS:-}${GIT_CONFIG_COUNT:-}"\n'
                    'test ! -f "$HOME/.npmrc"\n'
                    'test ! -f "$HOME/.m2/settings.xml"\n'
                )
            cli("npm", npm_check)
            cli("npx", npm_check)
            cli(
                "opencode",
                "test -z \"${OPENROUTER_API_KEY:-}\"\necho 'opencode v2.2.6'\n",
            )
            cli(
                "work",
                """
test "$OPENROUTER_API_KEY" = provider-fixture
test -z "${GH_TOKEN:-}${GITHUB_TOKEN:-}${MAVEN_GITHUB_PASSWORD:-}${GITHUB_ENV:-}"
test "$E2E_PROVISION" = never
"""
                'jq -e \'.workflow == "fix-issue" and .hosted and .environment.CIP_TARGET == "k8s" '
                'and (.environment | has("OPENROUTER_API_KEY") | not)\' "$1"\n'
                """result=$(jq -r '.output_directory' "$1")
mkdir "$result"
"""
                + f"printf '%s\\n' '{json.dumps({'status': status})}' > \"$result/report.json\"\nexit {exit_code}\n",
            )
            file(workspace / "e2e/package.json", '{"type":"module"}\n')
            file(
                workspace / "e2e/env/provision/check.ts",
                "export const expected = 'read-fixture';\n",
            )
            maven_check = "assert.equal(process.env.MAVEN_GITHUB_PASSWORD, expected);"
            if revision:
                maven_check = """
  for (const name of ['MAVEN_GITHUB_USERNAME', 'MAVEN_GITHUB_PASSWORD', 'GITHUB_TOKEN', 'GH_TOKEN',
                      'NODE_AUTH_TOKEN', 'AGENT_PUBLICATION_TOKEN', 'AGENT_VARIABLES_READ_TOKEN', 'MAVEN_OPTS',
                      'GIT_CONFIG_COUNT', 'NPM_CONFIG_USERCONFIG']) {
    assert.equal(process.env[name], undefined);
  }
  const args = process.env.MAVEN_ARGS.split(' ');
  const settings = args[args.indexOf('-s') + 1];
  assert.equal(settings, process.env.HOME + '/maven-settings.xml');
  assert.equal(settings, args[args.indexOf('-gs') + 1]);
  assert.equal(readFileSync(settings, 'utf8'), '<settings />\\n');
  assert.equal(statSync(settings).mode & 0o777, 0o400);
  const repository = args.find(arg => arg.startsWith('-Dmaven.repo.local=')).split('=')[1];
  assert.equal(readFileSync(repository + '/cached-dependency', 'utf8'), 'cached');
"""
            file(
                workspace / "e2e/env/provision/k8s.ts",
                """
import assert from 'node:assert/strict';
import { readFileSync, statSync } from 'node:fs';
import { expected } from './check.js';
export async function provisionK8s(mode: string) {
  assert.equal(mode, 'auto');
"""
                + maven_check
                + """
  assert.equal(process.env.E2E_BUILD, 'all');
  return { fixture: 'ready' };
}
""",
            )
            file(
                workspace / "e2e/support/schemas-build.ts",
                "export async function ensureSchemas() {}\n",
            )
            file(
                workspace / "e2e/support/ui-server.ts",
                """
import assert from 'node:assert/strict';
import { writeFileSync } from 'node:fs';
export async function ensureUiServer() {
  assert.equal(process.env.OPENROUTER_API_KEY, undefined);
  assert.equal(process.env.GH_TOKEN, undefined);
  assert.ok(Number(process.env.E2E_UI_PREVIEW_OWNER_PID) > 0);
  return { fixture: 'preview' };
}
export async function releaseUiServer(owner: number) {
  assert.equal(process.env.OPENROUTER_API_KEY, undefined);
  assert.equal(owner, Number(process.env.E2E_UI_PREVIEW_OWNER_PID));
  writeFileSync('preview-released', 'yes');
}
""",
            )

            environment = {
                **os.environ,
                "PATH": f"{tools}:{os.environ['PATH']}",
                "HOME": str(root / "home"),
                "JAVA_HOME": str(root / "java"),
                "GITHUB_RUN_ID": "20",
                "GITHUB_RUN_ATTEMPT": "1",
                "MAVEN_GITHUB_USERNAME": "fixture",
                "MAVEN_GITHUB_PASSWORD": "read-fixture",
            }
            if revision:
                file(root / "home/.npmrc", "npm-initial-dummy")
                file(
                    root / "home/.m2/settings.xml",
                    "<settings><servers>initial-dummy</servers></settings>",
                )
                file(
                    root / "runner/qip-revision-maven-repository/cached-dependency",
                    "cached",
                )
                environment.update(
                    RUNNER_TEMP=str(root / "runner"),
                    GITHUB_TOKEN="github-dummy",
                    GH_TOKEN="gh-dummy",
                    NODE_AUTH_TOKEN="npm-dummy",
                    MAVEN_ARGS="-s original-settings",
                    MAVEN_OPTS="maven-dummy",
                    NPM_CONFIG_USERCONFIG=str(root / "home/.npmrc"),
                    AGENT_PUBLICATION_TOKEN="publication-dummy",
                    AGENT_VARIABLES_READ_TOKEN="variables-dummy",
                    GIT_CONFIG_COUNT="0",
                )
            execute("git", "init", "-q", cwd=workspace)
            execute("git", "add", ".", cwd=workspace)
            execute(
                "git",
                "-c",
                "user.email=fixture@example.com",
                "-c",
                "user.name=Fixture",
                "commit",
                "-qm",
                "Fixture",
                cwd=workspace,
            )
            sha = execute("git", "rev-parse", "HEAD", cwd=workspace)
            checkpoint, issue = admitted_issue(sha=sha)
            hosted_issue.snapshot(checkpoint, issue, REPO, assignment, None)

            prepare = "hosted_prepare_revision.sh" if revision else "hosted_prepare.sh"
            execute("bash", SCRIPTS / prepare, trusted, workspace, hosted)
            runner = (hosted / "runner.json").read_text()
            self.assertIn("kind-qip-20-1", runner)
            self.assertNotIn("read-fixture", runner)
            if revision:
                self.assertNotIn("dummy", runner)
                self.assertIn("qip-revision-maven-repository", runner)
                self.assertIn(" -gs ", runner)
            self.assertEqual((hosted / "kubeconfig").read_text(), "fixture")
            environment.update(
                OPENROUTER_API_KEY="provider-fixture",
                GH_TOKEN="must-not-leak",
                GITHUB_TOKEN="must-not-leak",
                QIP_AGENT_MODEL="openrouter/openai/gpt-6-luna",
                QIP_AGENT_EFFORT="high",
                QIP_AGENT_REVIEW_MODEL="openrouter/openai/gpt-6-luna",
                QIP_AGENT_REVIEW_EFFORT="xhigh",
            )
            execute(
                "python3",
                SCRIPTS / "hosted_launch.py",
                workspace,
                assignment,
                hosted,
                tools / "work",
                tools / "opencode",
                expected=exit_code,
            )
            self.assertEqual(
                json.loads((hosted / "result/report.json").read_text())["status"],
                status,
            )
            self.assertEqual((workspace / "preview-released").read_text(), "yes")
            configuration = (hosted / "launcher-home/config.json").read_text()
            self.assertNotIn("provider-fixture", configuration)
            config = json.loads(configuration)
            self.assertEqual(
                config["profile"],
                {
                    "Runtime": "opencode",
                    "Model": "openrouter/openai/gpt-6-luna",
                    "Effort": "high",
                },
            )
            self.assertEqual(
                config["review_profile"], {**config["profile"], "Effort": "xhigh"}
            )


if __name__ == "__main__":
    unittest.main()

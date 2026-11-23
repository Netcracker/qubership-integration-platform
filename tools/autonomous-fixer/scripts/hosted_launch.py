import argparse
import json
import os
import signal
import subprocess
from pathlib import Path

PRODUCT_ENVIRONMENT = {
    "CIP_TARGET",
    "E2E_BROKERS",
    "E2E_BUILD",
    "E2E_PROVISION",
    "E2E_WORKERS",
    "JAVA_HOME",
    "KUBECONFIG",
    "MAVEN_ARGS",
    "PLAYWRIGHT_BROWSERS_PATH",
    "npm_config_cache",
}
OPENCODE_VERSION = "opencode v2.2.6"


def profile(settings, review=False):
    prefix = "QIP_AGENT_REVIEW_" if review else "QIP_AGENT_"
    model = settings[prefix + "MODEL"]
    if not model.startswith("openrouter/") or model.count("/") < 2:
        raise ValueError(f"{prefix}MODEL must name an OpenRouter model")
    return {
        "Runtime": "opencode",
        "Model": model,
        "Effort": settings.get(prefix + "EFFORT", ""),
    }


def product_environment(runner, workspace, preview_owner):
    if (
        runner["workspace"] != str(workspace)
        or runner["environment"]["CIP_TARGET"] != "k8s"
    ):
        raise ValueError(
            "The prepared cluster is bound to a different checkout or target"
        )
    return {
        **{key: runner["environment"][key] for key in PRODUCT_ENVIRONMENT},
        "E2E_UI_PREVIEW_OWNER_PID": str(preview_owner),
    }


def assignment_config(
    workspace, assignment, hosted, opencode, issue, runner, environment, settings
):
    return {
        "workflow": "fix-issue",
        "hosted": True,
        "workspace": str(workspace),
        "task_directory": str(assignment),
        "output_directory": str(hosted / "result"),
        "runtime_binary": str(opencode),
        "runtime_version": OPENCODE_VERSION,
        "profile": profile(settings),
        "review_profile": profile(settings, review=True),
        "task_id": issue["task_id"],
        "environment_id": runner["environment_id"],
        "environment": environment,
    }


def launch(workspace, assignment, hosted, work, opencode, settings):
    issue = json.loads((assignment / "issue.json").read_text(encoding="utf-8"))
    runner = json.loads((hosted / "runner.json").read_text(encoding="utf-8"))
    environment = product_environment(runner, workspace, os.getpid())
    config = assignment_config(
        workspace, assignment, hosted, opencode, issue, runner, environment, settings
    )
    provider_key = settings["OPENROUTER_API_KEY"]
    if not provider_key:
        raise ValueError("OPENROUTER_API_KEY is required")

    private_home = hosted / "launcher-home"
    private_home.mkdir(mode=0o700)
    shell_env = {
        "HOME": str(private_home),
        "PATH": runner["path"],
        "LANG": "C.UTF-8",
        **environment,
    }
    head = subprocess.check_output(
        ["git", "rev-parse", "HEAD"], cwd=workspace, env=shell_env, text=True
    ).strip()
    status = subprocess.check_output(
        ["git", "status", "--porcelain"], cwd=workspace, env=shell_env
    )
    if head != issue["base_sha"] or status:
        raise ValueError(
            "The prepared checkout no longer matches the admitted base commit"
        )
    version = subprocess.check_output(
        [opencode, "--version"], cwd=workspace, env=shell_env, text=True
    ).strip()
    if version != OPENCODE_VERSION:
        raise ValueError(
            f"Install {OPENCODE_VERSION} before launching the hosted worker"
        )

    config_path = private_home / "config.json"
    with config_path.open("x", encoding="utf-8") as config_file:
        json.dump(config, config_file, indent=2)
        config_file.write("\n")

    provisioner = Path(__file__).with_name("hosted_provision.mjs").resolve()
    preview_command = ["node", "--experimental-transform-types", str(provisioner)]
    stop_signal = None
    child = None

    def forward(signum, _frame):
        nonlocal stop_signal
        stop_signal = signum
        if child is not None and child.poll() is None:
            child.send_signal(signum)

    signal.signal(signal.SIGINT, forward)
    signal.signal(signal.SIGTERM, forward)
    preview_started = False
    try:
        subprocess.run(
            [*preview_command, "preview", workspace],
            cwd=workspace,
            env=shell_env,
            check=True,
        )
        preview_started = True
        if stop_signal is not None:
            return 128 + stop_signal
        with (hosted / "preparation" / "controller.log").open(
            "x", encoding="utf-8"
        ) as output:
            child = subprocess.Popen(
                [work, config_path],
                cwd=workspace,
                env={**shell_env, "OPENROUTER_API_KEY": provider_key},
                stdout=output,
                stderr=subprocess.STDOUT,
            )
            status = child.wait()
        if status:
            print(
                "Hosted work ended before reporting a fix. See the run report and controller log.",
                flush=True,
            )
        return status if status >= 0 else 128 - status
    finally:
        if preview_started:
            subprocess.run(
                [*preview_command, "release", workspace],
                cwd=workspace,
                env=shell_env,
                check=True,
            )


def main():
    parser = argparse.ArgumentParser(
        description="Run the hosted OpenCode issue assignment against the prepared stack"
    )
    for field in ("workspace", "assignment", "hosted", "work", "opencode"):
        parser.add_argument(field, type=Path)
    args = parser.parse_args()
    try:
        paths = [path.resolve(strict=True) for path in vars(args).values()]
        raise SystemExit(launch(*paths, os.environ))
    except (KeyError, OSError, ValueError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    main()

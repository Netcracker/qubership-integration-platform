import argparse
import hashlib
import json
import os
import re
from pathlib import Path
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

URL = re.compile(r'https://[^\s<>()"\']+')
REPOSITORY = re.compile(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
ASSET = re.compile(r"(?:assets/\d+/[\da-fA-F-]+|files/\d+/[^/]+)$")
ASSET_HOSTS = {
    "user-images.githubusercontent.com",
    "private-user-images.githubusercontent.com",
    "objects.githubusercontent.com",
    "github-production-user-asset-6210df.s3.amazonaws.com",
}
MAX_ATTACHMENT_BYTES = 25 * 1024 * 1024
MAX_ATTACHMENTS = 12
EXTENSIONS = {
    "image/png": "png",
    "image/jpeg": "jpg",
    "image/webp": "webp",
    "image/gif": "gif",
    "image/svg+xml": "svg",
    "text/plain": "txt",
    "text/csv": "csv",
    "application/json": "json",
    "application/pdf": "pdf",
    "application/zip": "zip",
    "video/mp4": "mp4",
}


def attachment_url(url, repository=None):
    try:
        parsed = urlsplit(url)
    except ValueError:
        return False
    if parsed.scheme != "https" or parsed.netloc != parsed.hostname:
        return False
    if parsed.hostname in ASSET_HOSTS:
        return bool(parsed.path.strip("/"))
    if parsed.hostname != "github.com":
        return False
    if parsed.path.startswith("/user-attachments/"):
        return bool(
            re.fullmatch(
                r"(?:assets/[\da-fA-F-]+|files/\d+/[^/]+)",
                parsed.path.removeprefix("/user-attachments/"),
            )
        )
    return (
        repository is not None
        and parsed.path.startswith(f"/{repository}/")
        and bool(ASSET.fullmatch(parsed.path.removeprefix(f"/{repository}/")))
    )


class AttachmentRedirect(HTTPRedirectHandler):
    def __init__(self, repository):
        self.repository = repository

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if not attachment_url(newurl, self.repository):
            raise ValueError("Attachment redirect leaves GitHub's asset hosts")
        redirected = super().redirect_request(req, fp, code, msg, headers, newurl)
        if redirected is not None:
            redirected.remove_header("Authorization")
        return redirected


def download(url, repository, token):
    request = Request(url, headers={"User-Agent": "QIP issue capture"})
    if token and urlsplit(url).hostname == "github.com":
        request.add_unredirected_header("Authorization", f"Bearer {token}")
    with build_opener(AttachmentRedirect(repository)).open(
        request, timeout=30
    ) as response:
        content_type = response.headers.get_content_type()
        data = response.read(MAX_ATTACHMENT_BYTES + 1)
    if not data or len(data) > MAX_ATTACHMENT_BYTES:
        raise ValueError("Attachment is empty or exceeds 25 MiB")
    return content_type, data


def write_json(path, value):
    with path.open("x", encoding="utf-8") as output:
        json.dump(value, output, indent=2, ensure_ascii=False)
        output.write("\n")


def render_answers(checkpoint):
    answers = checkpoint.get("answers", [])
    if not answers:
        return ""
    sections = ["\n## Authorized issue responses\n"]
    for record in answers:
        if record["contractVersion"] != checkpoint["contractVersion"]:
            raise ValueError(
                "An issue response belongs to a different contract version"
            )
        sections.append(
            f"\n**{record['questionId']}**, version {record['questionVersion']}\n\n"
            "Question\n\n"
            + "\n".join(f"> {line}" for line in record["question"].split("\n"))
            + "\n\n"
            f"Answer from @{record['actor']}\n\n"
            + "\n".join(f"> {line}" for line in record["answer"].split("\n"))
            + "\n"
        )
    return "".join(sections)


def render_revision(revision, checkpoint, repository):
    command = revision["commentId"]
    pull = revision["pullNumber"]
    if (
        revision["head"] != checkpoint["contract"]["baseSha"]
        or revision["issue"] != checkpoint["issueNumber"]
    ):
        raise ValueError("The revision does not match the admitted pull request head")
    origin = revision["originRun"]
    pr = f"https://github.com/{repository}/pull/{pull}"
    evidence = f"https://github.com/{repository}/actions/runs/{origin['runId']}/attempts/{origin['runAttempt']}"
    request = "Automatic request" if revision.get("automatic") else "Maintainer request"
    sections = [
        "\n## Pull request revision\n",
        f"\n[{request}]({pr}#issuecomment-{command}) at `{revision['head']}`.\n",
        f"\n[Initial publication evidence]({evidence}).\n",
        (
            "\nContinue from the checked-out pull request head. Address the selected feedback, "
            "preserve existing work, and keep the original issue requirements and approved paths. "
            "Reproduce the behavior described in the feedback where applicable. "
            "Repeat the relevant product checks for the original issue and the revision, "
            "and ask the reviewer to check both. "
            "Leave discussion resolution, pull request status, and merge to the maintainer.\n"
        ),
    ]
    if instruction := revision.get("instruction"):
        sections.append(
            "\n### Maintainer instruction\n\n"
            + "\n".join(f"> {line}" for line in instruction.splitlines())
            + "\n"
        )
    sections.append("\n### Selected feedback\n")
    for feedback in revision["feedback"]:
        location = ""
        if path := feedback.get("path"):
            line = feedback.get("line")
            location = f" (`{path}:{line}`)" if line else f" (`{path}`)"
        excerpt = "\n".join(f"> {line}" for line in feedback["body"].splitlines())
        label = "CI check" if feedback.get("kind") == "ci" else "Discussion"
        reference = f"[{label}]({feedback['url']})" if feedback["url"] else label
        sections.append(f"\n{reference}{location}\n\n{excerpt}\n")
    return "".join(sections)


def snapshot(
    checkpoint, issue, repository, directory, token, fetch=download, revision=None
):
    issue_number = checkpoint["issueNumber"]
    contract = checkpoint["contract"]
    if (
        not REPOSITORY.fullmatch(repository)
        # pylint: disable-next=unidiomatic-typecheck
        or type(issue_number) is not int
        or issue_number < 1
    ):
        raise ValueError("The checkpoint must name a repository issue")
    if (
        issue["number"] != issue_number
        or issue["state"] != "open"
        or "pull_request" in issue
    ):
        raise ValueError("The admitted issue is no longer open")
    if not any(label["name"] == "ready-for-agent" for label in issue["labels"]):
        raise ValueError("The admitted issue no longer has the ready-for-agent label")
    requirements = f"{issue['title']}\n\n{issue['body'] or ''}"
    if "requirementsSha256" in contract:
        requirements_current = (
            hashlib.sha256(requirements.encode("utf-8")).hexdigest()
            == contract["requirementsSha256"]
        )
    else:
        requirements_current = requirements == contract["requirements"]
    if not requirements_current:
        raise ValueError("The issue title or description changed since admission")
    answers = render_answers(checkpoint)
    scope_paths = contract["scopePaths"]
    links = (raw.rstrip(".,;") for raw in URL.findall(requirements + answers))
    urls = list(
        dict.fromkeys(link for link in links if attachment_url(link, repository))
    )
    if len(urls) > MAX_ATTACHMENTS:
        raise ValueError(f"Issue links more than {MAX_ATTACHMENTS} GitHub attachments")
    directory.mkdir(mode=0o700)
    records = []
    for index, url in enumerate(urls, start=1):
        try:
            content_type, data = fetch(url, repository, token)
        except (OSError, ValueError):
            records.append({"index": index, "status": "unavailable"})
            continue
        name = f"attachment-{index:02d}.{EXTENSIONS.get(content_type, 'bin')}"
        (directory / name).write_bytes(data)
        records.append(
            {
                "index": index,
                "status": "captured",
                "path": name,
                "content_type": content_type,
                "size": len(data),
                "sha256": hashlib.sha256(data).hexdigest(),
            }
        )
    write_json(directory / "attachments.json", records)
    if any(record["status"] != "captured" for record in records):
        raise ValueError(
            "Issue attachments are unavailable. Inspect attachments.json before retrying"
        )
    task = f"# Issue #{issue_number}\n\nhttps://github.com/{repository}/issues/{issue_number}\n"
    if scope_paths:
        task += "\n## Approved change paths\n\n" + "".join(
            f"- `{path}`\n" for path in scope_paths
        )
    task += f"\n## Issue title and description\n\n{requirements}\n"
    task += answers
    if revision is not None:
        task += render_revision(revision, checkpoint, repository)
    if answers:
        write_json(directory / "answers.json", checkpoint["answers"])
    if records:
        task += "\n## Captured attachments\n\n" + "".join(
            f"- `{record['path']}` ({record['content_type']})\n" for record in records
        )
    (directory / "task.md").write_text(task, encoding="utf-8")
    write_json(
        directory / "issue.json",
        {
            "repository": repository,
            "number": issue_number,
            "base_sha": contract["baseSha"],
            "task_id": checkpoint["taskId"],
            "scope_paths": scope_paths,
        },
    )


def main():
    parser = argparse.ArgumentParser(
        description="Capture the admitted GitHub issue and its attachments"
    )
    parser.add_argument("checkpoint", type=Path)
    parser.add_argument("issue", type=Path)
    parser.add_argument("assignment", type=Path)
    parser.add_argument("--revision", type=Path)
    args = parser.parse_args()
    try:
        checkpoint = json.loads(args.checkpoint.read_text(encoding="utf-8"))
        issue = json.loads(args.issue.read_text(encoding="utf-8"))
        revision = None
        if args.revision is None and "revisionSha256" in checkpoint:
            raise ValueError("The admitted revision requires its selected feedback")
        if args.revision is not None:
            data = args.revision.read_bytes().rstrip(b"\n")
            if hashlib.sha256(data).hexdigest() != checkpoint.get("revisionSha256"):
                raise ValueError(
                    "The selected feedback differs from the admitted revision"
                )
            revision = json.loads(data)
        snapshot(
            checkpoint,
            issue,
            os.environ["GITHUB_REPOSITORY"],
            args.assignment,
            os.getenv("GH_TOKEN"),
            revision=revision,
        )
    except (KeyError, OSError, ValueError) as error:
        parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
INSTALL_GOAL = "org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file"


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def identity(root):
    return {
        "commit": subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip(),
        "run": os.environ.get("GITHUB_RUN_ID", "local"),
        "attempt": os.environ.get("GITHUB_RUN_ATTEMPT", "1"),
        "wrapper": digest(root / ".mvn/wrapper/maven-wrapper.properties"),
    }


def bundle_directory():
    return Path(os.environ["MAVEN_ANALYSIS_DIRECTORY"])


def export_project(arguments):
    bundle = bundle_directory()
    bundle.mkdir(parents=True, exist_ok=True)
    manifest_path = bundle / "manifest.json"
    manifest = (
        json.loads(manifest_path.read_text())
        if manifest_path.exists()
        else {"identity": identity(ROOT), "module": os.environ["MAVEN_ANALYSIS_MODULE"], "projects": []}
    )
    pom = Path(arguments.pom)
    flattened = pom.parent / ".flattened-pom.xml"
    if flattened.is_file():
        pom = flattened
    artifact = pom if arguments.packaging == "pom" else Path(arguments.artifact)
    destination = Path("reactor", arguments.group, arguments.name, arguments.version)
    (bundle / destination).mkdir(parents=True, exist_ok=True)
    extension = artifact.suffix
    artifact_path = destination / ("artifact" + extension)
    pom_path = destination / "pom.xml"
    shutil.copyfile(artifact, bundle / artifact_path)
    shutil.copyfile(pom, bundle / pom_path)
    manifest["projects"].append(
        {
            "group": arguments.group,
            "name": arguments.name,
            "version": arguments.version,
            "packaging": arguments.packaging,
            "artifact": artifact_path.as_posix(),
            "pom": pom_path.as_posix(),
        }
    )
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")


def seal_bundle():
    bundle = bundle_directory()
    manifest_path = bundle / "manifest.json"
    manifest = json.loads(manifest_path.read_text())
    target = ROOT / manifest["module"] / "target"
    for name in [
        "classes",
        "test-classes",
        "generated-sources",
        "generated-test-sources",
        "site",
        "surefire-reports",
        "jacoco.exec",
    ]:
        source = target / name
        destination = bundle / "analysis" / name
        if source.is_dir():
            shutil.copytree(source, destination)
        elif source.is_file():
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, destination)
    if not (bundle / "analysis/classes").is_dir():
        raise ValueError("The analysis bundle has no compiled classes.")
    manifest["files"] = {
        path.relative_to(bundle).as_posix(): digest(path)
        for path in sorted(bundle.rglob("*"))
        if path.is_file() and path != manifest_path
    }
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"Exported {len(manifest['projects'])} reactor artifacts for {manifest['module']}.")


def restore_bundle(root=ROOT):
    bundle = bundle_directory()
    manifest = json.loads((bundle / "manifest.json").read_text())
    current = identity(root)
    if any(manifest["identity"][field] != current[field] for field in ["commit", "run", "wrapper"]):
        raise ValueError("The analysis bundle belongs to another checkout, run, or Maven toolchain.")
    for relative, expected in manifest["files"].items():
        if digest(bundle / relative) != expected:
            raise ValueError(f"The analysis bundle file {relative} has a different digest.")
    with tempfile.TemporaryDirectory(prefix="maven-analysis-") as directory:
        pom = Path(directory) / "pom.xml"
        pom.write_text(
            "<project><modelVersion>4.0.0</modelVersion><groupId>ci.analysis</groupId>"
            "<artifactId>import</artifactId><version>1</version><packaging>pom</packaging></project>"
        )
        for project in manifest["projects"]:
            subprocess.run(
                [
                    str(root / "scripts/maven-ci.sh"),
                    "--file",
                    str(pom),
                    INSTALL_GOAL,
                    f"-Dfile={bundle / project['artifact']}",
                    f"-DpomFile={bundle / project['pom']}",
                    f"-DgroupId={project['group']}",
                    f"-DartifactId={project['name']}",
                    f"-Dversion={project['version']}",
                    f"-Dpackaging={project['packaging']}",
                    "-DgeneratePom=false",
                ],
                check=True,
                cwd=directory,
            )
    shutil.copytree(bundle / "analysis", root / manifest["module"] / "target", dirs_exist_ok=True)
    print(f"Restored analysis inputs for {manifest['module']} from {manifest['identity']['commit']}.")


def main():
    parser = argparse.ArgumentParser(description="Transfer the current Maven reactor outputs to the analysis job.")
    commands = parser.add_subparsers(dest="command", required=True)
    export = commands.add_parser("export")
    for name in ["group", "name", "version", "packaging", "artifact", "pom"]:
        export.add_argument(name)
    commands.add_parser("seal")
    commands.add_parser("restore")
    arguments = parser.parse_args()
    if arguments.command == "export":
        export_project(arguments)
    elif arguments.command == "seal":
        seal_bundle()
    else:
        restore_bundle()


if __name__ == "__main__":
    main()

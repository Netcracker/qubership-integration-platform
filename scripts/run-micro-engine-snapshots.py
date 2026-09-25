#!/usr/bin/env python3
"""Run the Maven snapshot profile with the selected number of JVM workers."""

import argparse
import os
from pathlib import Path
import sys


def positive_integer(value):
    number = int(value)
    if number < 1:
        raise argparse.ArgumentTypeError("Worker count must be positive.")
    return number


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--workers", type=positive_integer, default=6)
    parser.add_argument("maven_arguments", nargs=argparse.REMAINDER,
                        help="Maven options after --, including snapshot filters.")
    arguments = parser.parse_args()
    maven_arguments = arguments.maven_arguments
    if maven_arguments[:1] == ["--"]:
        maven_arguments = maven_arguments[1:]

    repo_root = Path(__file__).resolve().parent.parent
    wrapper = repo_root / ("mvnw.cmd" if os.name == "nt" else "mvnw")
    command = [str(wrapper), "-q", "-B", "-Dstyle.color=never", "-pl", "micro-engine",
               "-PsnapshotTests", "-Dgpg.skip=true", f"-Dsnapshot.workers={arguments.workers}",
               *maven_arguments, "test"]
    try:
        os.chdir(repo_root)
        os.execv(wrapper, command)
    except OSError as error:
        print(f"Cannot start Maven: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())

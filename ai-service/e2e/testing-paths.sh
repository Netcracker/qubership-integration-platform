#!/usr/bin/env bash
# Live chain cases are supplied by the skills package, outside service resources.
QIP_SERVICE_ROOT="${QIP_SERVICE_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
QIP_SKILLS_TESTING_ROOT="${QIP_SKILLS_TESTING_ROOT:-${QIP_SERVICE_ROOT}/.private/rocky-skills/testing}"
export QIP_SERVICE_ROOT QIP_SKILLS_TESTING_ROOT

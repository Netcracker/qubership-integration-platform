#!/usr/bin/env bash
#
# POM readers and writers, sourced by the version scripts. Every function runs
# from the repository root and reads or writes one POM with grep, sed, or awk.
#
# The readers stop a value at the next "<", so a CRLF checkout returns no "\r".
# The writers replace text inside one line and keep its line ending.
#
# Usage: . "$ROOT/scripts/pom.sh"
#
# Running this file does nothing. It keeps the executable bit because
# super-linter's BASH_EXEC rule requires it of every .sh in the repo.

pom_prop() { # <pom> <tag> -> the first value of <tag>, or nothing
    grep -oP "(?<=<$2>)[^<]+" "$1" | head -1 || true
}

pom_props() { # <pom> -> name=value per entry of the <properties> block
    sed -n '/<properties>/,/<\/properties>/p' "$1" |
        grep -oP '<\K[A-Za-z0-9._-]+>[^<]*(?=</)' |
        sed 's/>/=/' || true
}

pom_artifact() { # <pom> -> the POM's own artifactId
    sed '/<parent>/,/<\/parent>/d' "$1" | grep -oP '(?<=<artifactId>)[^<]+' | head -1 || true
}

pom_own_version() { # <pom> -> the first <version> outside <parent>
    sed '/<parent>/,/<\/parent>/d' "$1" | grep -oP '(?<=<version>)[^<]+' | head -1 || true
}

pom_parent_version() { # <pom> -> the <version> inside <parent>
    sed -n '/<parent>/,/<\/parent>/p' "$1" | grep -oP '(?<=<version>)[^<]+' | head -1 || true
}

pom_set_own_version() { # <pom> <version>
    local tmp
    tmp="$(mktemp)"
    awk -v ver="$2" '
        /<parent>/ { in_parent = 1 }
        !in_parent && !done && /<version>[^<]*<\/version>/ {
            sub(/<version>[^<]*<\/version>/, "<version>" ver "</version>")
            done = 1
        }
        /<\/parent>/ { in_parent = 0 }
        { print }
    ' "$1" > "$tmp"
    mv "$tmp" "$1"
}

pom_set_parent_version() { # <pom> <version>
    sed -i -E "/<parent>/,/<\/parent>/s#<version>[^<]+</version>#<version>$2</version>#" "$1"
}

bom_artifacts() { # -> every artifactId in the BOM's dependencyManagement block
    # The block opens on a line of its own: the flatten plugin's configuration
    # holds a one-line <dependencyManagement>resolve</dependencyManagement>.
    sed -n '/<dependencyManagement>[[:space:]]*$/,/<\/dependencyManagement>/p' bom/pom.xml |
        grep -oP '(?<=<artifactId>)[^<]+' || true
}

bom_entry() { # <artifactId> -> the version text of its BOM entry, or nothing
    awk -v id="$1" '
        /<dependencyManagement>[[:space:]]*$/ { inside = 1 }
        /<\/dependencyManagement>/ { inside = 0 }
        inside && /<artifactId>/ {
            artifact = $0
            sub(/.*<artifactId>/, "", artifact)
            sub(/<\/artifactId>.*/, "", artifact)
        }
        inside && artifact == id && /<version>/ {
            version = $0
            sub(/.*<version>/, "", version)
            sub(/<\/version>.*/, "", version)
            print version
            exit
        }
    ' bom/pom.xml
}

bom_set_entry() { # <artifactId> <X.Y.Z> -> 1 when the BOM lists no such artifact
    local tmp
    tmp="$(mktemp)"
    # BINMODE=3 keeps the carriage return of a CRLF line where gawk reads text mode (Git Bash on Windows).
    if awk -v BINMODE=3 -v id="$1" -v ver="$2" '
        /<dependencyManagement>[[:space:]]*$/ { inside = 1 }
        /<\/dependencyManagement>/ { inside = 0 }
        inside && /<artifactId>/ {
            artifact = $0
            sub(/.*<artifactId>/, "", artifact)
            sub(/<\/artifactId>.*/, "", artifact)
        }
        inside && !done && artifact == id && /<version>/ {
            sub(/<version>[0-9]+\.[0-9]+\.[0-9]+/, "<version>" ver)
            done = 1
        }
        { print }
        END { exit done ? 0 : 1 }
    ' bom/pom.xml > "$tmp"; then
        mv "$tmp" bom/pom.xml
    else
        rm -f "$tmp"
        return 1
    fi
}

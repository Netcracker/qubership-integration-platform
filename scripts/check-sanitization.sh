#!/usr/bin/env bash
#
# Refuse content that carries vendor-internal identifiers.
#
# The forbidden strings live outside the repository: writing them down here would
# put them in the very history this check keeps clean. Point
# QIP_SANITIZATION_TOKENS at the list, or leave it at the default path below.
# Without a readable list the check fails rather than passes.
#
# List format: one case-insensitive extended regular expression per line, under a
# [deny] or an [allow] section. Blank lines and lines starting with '#' are
# ignored. An [allow] match exempts a [deny] match that falls entirely inside it,
# so a carve-out cannot hide a longer forbidden string that merely contains it.
#
# Usage:
#   check-sanitization.sh --staged        # what git is about to commit
#   check-sanitization.sh --tracked       # every tracked file in the work tree
#   check-sanitization.sh --stdin         # a stream, such as `git log -p`
#   check-sanitization.sh FILE...         # the given files
#   check-sanitization.sh --install-hook  # run this check on every commit
#
# Exit codes: 0 clean, 1 forbidden content found, 2 the check could not run.

set -euo pipefail

tokens_path="${QIP_SANITIZATION_TOKENS:-$HOME/.config/qip-sanitization-tokens.txt}"

bail() {
    printf '%s\n' "$*" >&2
    exit 2
}

usage() {
    cat << 'USAGE'
Usage:
  check-sanitization.sh --staged        # what git is about to commit
  check-sanitization.sh --tracked       # every tracked file in the work tree
  check-sanitization.sh --stdin         # a stream, such as `git log -p`
  check-sanitization.sh FILE...         # the given files
  check-sanitization.sh --install-hook  # run this check on every commit

Set QIP_SANITIZATION_TOKENS to the token list. Exit codes: 0 clean,
1 forbidden content found, 2 the check could not run.
USAGE
}

# The versioned .githooks/pre-commit chains any global hook the developer already
# has, so pointing core.hooksPath at it takes nothing away.
install_hook() {
    local root configured
    root="$(git rev-parse --show-toplevel)" ||
        bail "Not inside a git repository. Run --install-hook from a clone."
    [ -x "$root/.githooks/pre-commit" ] ||
        bail "Missing the versioned hook at '$root/.githooks/pre-commit'."
    configured="$(git -C "$root" config --local core.hooksPath || true)"
    if [ -n "$configured" ] && [ "$configured" != ".githooks" ]; then
        bail "This clone already sets core.hooksPath to '$configured'. Chain the sanitization check from that directory by hand."
    fi
    git -C "$root" config core.hooksPath .githooks
    printf 'Pointed core.hooksPath at .githooks; the sanitization check now runs on every commit in this clone.\n'
}

[ $# -gt 0 ] || {
    usage >&2
    exit 2
}

if [ "$1" = "--install-hook" ]; then
    install_hook
    exit 0
fi

if [ "$1" = "-h" ] || [ "$1" = "--help" ]; then
    usage
    exit 0
fi

[ -r "$tokens_path" ] ||
    bail "Cannot read the sanitization token list at '$tokens_path'. Set QIP_SANITIZATION_TOKENS to its location; without the list this check cannot pass."

workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT
manifest="$workdir/manifest"
: > "$manifest"
blob_count=0

# Record a label shown in the report and the file actually scanned.
record() {
    printf '%s\t%s\n' "$1" "$2" >> "$manifest"
}

record_staged() {
    local path blob
    while IFS= read -r -d '' path; do
        blob_count=$((blob_count + 1))
        blob="$workdir/blob.$blob_count"
        # Skipping an unreadable blob would drop it from the scan silently, so
        # a staged path this cannot read fails the check instead.
        git show ":$path" > "$blob" ||
            bail "Cannot read the staged content of '$path'. Re-stage the file; the check does not pass on content it could not scan."
        record "$path" "$blob"
    done < <(git diff --cached --name-only --diff-filter=ACMR -z)
}

record_tracked() {
    local root path
    root="$(git rev-parse --show-toplevel)" || bail "Not inside a git repository."
    while IFS= read -r -d '' path; do
        [ -f "$root/$path" ] || continue
        record "$path" "$root/$path"
    done < <(git -C "$root" ls-files -z)
}

case "$1" in
    --staged) record_staged ;;
    --tracked) record_tracked ;;
    --stdin)
        cat > "$workdir/stdin"
        record "<stdin>" "$workdir/stdin"
        ;;
    -*) bail "Unknown option '$1'. Run --help for usage." ;;
    *)
        for path in "$@"; do
            [ -r "$path" ] || bail "Cannot read '$path'."
            record "$path" "$path"
        done
        ;;
esac

cat > "$workdir/scan.pl" << 'SCAN'
use strict;
use warnings;

my ($tokens_path, $manifest_path) = @ARGV;

sub bail {
    print STDERR "@_\n";
    exit 2;
}

my (@deny, @allow);
open(my $tokens, '<', $tokens_path) or bail("Cannot read the token list.");
my $section = '';
while (my $line = <$tokens>) {
    chomp $line;
    $line =~ s/^\s+|\s+$//g;
    next if $line eq '' || $line =~ /^#/;
    if ($line =~ /^\[(\w+)\]$/) {
        $section = lc($1);
        bail("Unknown section on line $. of the token list.")
            unless $section eq 'deny' || $section eq 'allow';
        next;
    }
    bail("Pattern outside a [deny] or [allow] section on line $. of the token list.")
        if $section eq '';
    my $pattern = eval { qr/$line/i };
    bail("Invalid regular expression on line $. of the token list.") unless defined $pattern;
    push @{ $section eq 'deny' ? \@deny : \@allow }, $pattern;
}
close($tokens);
bail("The token list declares no [deny] patterns.") unless @deny;

# Byte offsets of every match of every pattern in one line.
sub spans {
    my ($text, $patterns) = @_;
    my @found;
    for my $pattern (@$patterns) {
        pos($text) = 0;
        while ($text =~ /$pattern/g) {
            my ($from, $to) = ($-[0], $+[0]);
            push @found, [ $from, $to ];
            pos($text) = $to > $from ? $to : $from + 1;
            last if pos($text) > length($text);
        }
    }
    return \@found;
}

my $violations = 0;
open(my $manifest, '<', $manifest_path) or bail("Cannot read the scan manifest.");
while (my $entry = <$manifest>) {
    chomp $entry;
    my ($label, $path) = split(/\t/, $entry, 2);
    open(my $fh, '<', $path) or bail("Cannot read '$label'.");
    binmode($fh);
    my $lineno = 0;
    while (my $line = <$fh>) {
        $lineno++;
        next if index($line, "\0") >= 0;
        my $hits = spans($line, \@deny);
        next unless @$hits;
        my $exempt = spans($line, \@allow);
        for my $hit (@$hits) {
            next if grep { $_->[0] <= $hit->[0] && $_->[1] >= $hit->[1] } @$exempt;
            print STDERR "$label:$lineno: forbidden identifier\n";
            $violations++;
            last;
        }
    }
    close($fh);
}
close($manifest);
exit($violations ? 1 : 0);
SCAN

status=0
perl "$workdir/scan.pl" "$tokens_path" "$manifest" || status=$?

if [ "$status" -eq 1 ]; then
    printf 'Forbidden identifiers reached the lines above. The matches themselves are not printed, because printing them would leak them; compare those lines against %s and remove the identifier before committing.\n' "$tokens_path" >&2
fi

exit "$status"

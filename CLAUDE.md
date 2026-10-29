# CLAUDE.md

Claude Code takes this repository's instructions from `.claude/rules/`, which `apm install` generates from
`.apm/instructions/`. The `AGENTS.md` files carry the same text for Codex and Cursor. Claude Code reads `AGENTS.md`
only when no `CLAUDE.md` exists, so this file keeps it from loading every instruction twice. Edit instructions under
`.apm/`, not in `.claude/rules/` or in a generated `AGENTS.md`.

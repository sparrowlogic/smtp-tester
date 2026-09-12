# CLAUDE.md

The guidance for this repository lives in [AGENTS.md](AGENTS.md), so that it applies to every
tool rather than just this one. Read it before making a change.

@AGENTS.md

## The one rule that is not negotiable

Run `make agent-completion` after your last edit. If it does not exit 0, the work is not finished —
keep going. Report the result honestly: if it fails, say what failed and show the output rather
than describing the change as done.

A `Stop` hook in `.claude/settings.json` runs this same command automatically and will block the
turn from ending while it fails, so running it yourself is how you find out first rather than a
formality.

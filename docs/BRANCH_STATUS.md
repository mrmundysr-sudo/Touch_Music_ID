# Branch Status and Development Handoff

> This file is a handoff guide, not the source of truth. Verify every branch, PR, commit, and CI result against GitHub before acting. Never infer that the default branch or most recent branch contains the newest app code.

## Current handoff (fill with verified facts)
- Default branch: `main` (template default; verify repository settings)
- Active development branch: **Not established**
- Latest verified APK branch / commit: **Not established**
- Latest successful GitHub Actions run / artifact: **Not established**
- Open PRs and their base/head: **Check GitHub**
- Recommended starting ref for next task: **Determine after verification**
- Current approved task: **See docs/DEVELOPMENT_PLAN.md and owner instructions**
- Last reviewed (UTC): **Not reviewed**
- Evidence links: **None yet**

## Mandatory session startup
1. Confirm exact repository and default branch with GitHub.
2. Read this file, `CLAUDE.md`, the development plan, decision log, and relevant work reports.
3. Enumerate remote branches and open PRs. Fetch remote refs; a docs-only checkout does **not** prove the app source is missing.
4. Locate app source, build workflow, and recent successful APK artifacts. Check branch relationships, commit ancestry, and unmerged changes.
5. Compare this handoff against live GitHub data. If stale or ambiguous, report the conflict and choose no branch until it is resolved.
6. Select an appropriate verified base; create an isolated task branch. Do not overwrite existing development work.
7. Before coding, report chosen repository, base branch, commit SHA, and rationale.

## Mandatory task completion
- Record development branch and HEAD SHA; base branch; PR link if any.
- Record workflow name, run URL, conclusion, and artifact link **only after verification**.
- Summarize changes, tests actually executed, known limitations, and next task.
- Update this handoff in the task branch or an approved documentation branch; avoid overwriting concurrent edits.
- A successful build is not proof that its branch contains the latest changes.
- Never automatically merge, reset, force-push, delete branches, or release.

## Future automation contract
A reusable workflow may produce **read-only** branch/build status as a CI artifact after a completed successful APK build. Prefer this to auto-committing status changes to `main`. Include repository, source ref, SHA, run ID/URL, conclusion, timestamp, and APK artifact name. Treat the result as a historical build record, not as the authoritative active development branch. Do not install a workflow until its permissions, existing build triggers, and artifact names are inspected and verified.

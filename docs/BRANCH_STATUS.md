# Branch Status and Development Handoff

> Handoff guide, not the source of truth. Verify every branch, PR, commit and CI result against GitHub before acting.

## Current handoff (verified 2026-11-02 against live GitHub)
- Default branch: `main` @ `f1caaad` (docs only; unchanged by this work; **not merged into**)
- Active development branch: **`claude/touch-music-id-first-build`** (all app source lives here only)
- Source commit of the delivered debug APK: **`1c48530d2ad05fae813f0735b8fd84bc97d701d4`**
- Commits after the source commit are documentation/config only (work report, this file, `deliver.conf`, README counts) and do not change the app
- Open PRs: none (none created; the owner decides whether to open one)
- Latest app source: this branch. Latest successful APK: CI run for the source commit (see below)
- Workflow: `.github/workflows/autobuild.yml` (self-contained: unit tests, informational live Wikipedia smoke, debug APK). The shared hub workflow is not used because this repo is public and the hub is currently private
- Recommended starting ref for the next task: `claude/touch-music-id-first-build`
- Current approved task: first Android debug APK (done pending owner phone test). Phase 2 (real recognition) is **not** approved yet

## CI evidence
| Run | Commit | Result |
|---|---|---|
| [#1](https://github.com/mrmundysr-sudo/Touch_Music_ID/actions/runs/41384170213) | `5f15dbf` | **failed at startup, 0 jobs** (hub reusable workflow; cause inferred: public repo → private hub) |
| [#2](https://github.com/mrmundysr-sudo/Touch_Music_ID/actions/runs/41384578939) | `256a622` | success (self-contained workflow) |
| [#6](https://github.com/mrmundysr-sudo/Touch_Music_ID/actions/runs/41387059607) | `71d32c4` | success: unit tests, APK, live Wikipedia smoke (real-API output in log) |
| [#8](https://github.com/mrmundysr-sudo/Touch_Music_ID/actions/runs/41387637765) | `1c48530` (source commit) | **success**: Unit tests, Live Wikipedia smoke, Debug APK (artifact `Touch-Music-ID-debug-8`) |

## Mandatory session startup
1. Confirm repo and default branch. 2. Read this file, CLAUDE.md, plan, decisions, `docs/knowledge/`. 3. Enumerate branches/PRs, fetch remote refs.
4. Locate source, workflow, latest successful APK. 5. Compare this handoff to GitHub; if stale or ambiguous, stop and report.
6. Branch from a verified base; never overwrite existing work. 7. Report repo, base branch, SHA and rationale before coding.

## Mandatory task completion
Record branch and HEAD SHA, base, PR link if any; workflow run URL, conclusion and artifact only after verification; changes, tests actually run, limitations, next task.
Never automatically merge, reset, force-push, delete branches or release.

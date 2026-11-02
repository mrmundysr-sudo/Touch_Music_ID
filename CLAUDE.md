# Mundy Morning Studios — Claude Development Instructions

This is a **project template**, not an implementation request.

## Read before any work
1. `docs/PRODUCT_VISION.md` — product intent and scope.
2. `docs/DECISIONS.md` — approved decisions, rejected proposals, open questions.
3. `docs/DEVELOPMENT_PLAN.md` — authorized milestones and acceptance criteria.
4. `README.md` — current repository status.

## Non-negotiable workflow
- Do not begin implementing a brainstormed feature just because it appears in a document.
- Confirm approved scope before coding. Ask when requirements are ambiguous.
- Verify repository, branch, CI workflow, and existing behavior.
- GitHub is the single source of truth.
- Work on an isolated development branch; never merge to main or publish releases without explicit owner approval.
- Reuse shared components only after inspecting source and licenses. Never assume another app's engine is directly reusable.
- Make incremental changes; run tests; inspect GitHub Actions logs; correct build failures; verify the APK path or private release before reporting success.
- Record approved product decisions in `docs/DECISIONS.md` and update the development plan when authorized scope changes.
- Preserve privacy, user-owned exports, and backward compatibility as specified for the app.
- Explain risks and tradeoffs clearly; do not silently add complexity.

## Starting a new app
- Replace bracketed placeholders in the three docs with facts from brainstorming.
- Mark unapproved ideas as **Proposed**.
- Produce a feasibility audit and implementation proposal for review.
- Wait for approval before beginning code changes.

## Mundy Morning Studios
Android first; professional quality, accessible experiences, free core offerings as approved for each app. Shared toolkit and reusable modules are encouraged, not mandatory.

## Mandatory persistent development knowledge
- Before an authorized coding task, read docs/knowledge/README.md and relevant verified patterns, lessons, known issues, testing history and prior reports.
- After each substantial authorized assignment or batch, create a Markdown work report from docs/knowledge/WORK_REPORT_TEMPLATE.md in docs/knowledge/reports/.
- Record owner punch lists, actual fixes, regression outcomes and links to test/build evidence.
- Promote only **verified reusable findings** to PATTERNS.md and LESSONS_LEARNED.md; update issue and testing registers.
- Flag potentially studio-wide lessons for review, but never modify other repositories without explicit authorization.
- Documentation is external memory, not automatic model learning. Do not fabricate test results or expose secrets.

## Mandatory component-first development
Before writing substantial new functionality, read docs/COMPONENT_REGISTRY.md and consult the central Touch-Modules registry. Prefer verified compatible existing components. Inspect actual source and evidence; record reasons when reuse is inappropriate. Propose tested new components for registration; do not edit other repositories without approval.

## Mandatory branch discovery and handoff
- Read `docs/BRANCH_STATUS.md` before choosing a development base. It is a handoff hint, **not** authoritative state.
- Query live GitHub branches, open PRs, commit relationships, workflow runs and artifacts; fetch remote refs even when the initial checkout is docs-only.
- Identify the latest app source separately from the latest successful APK. Do not assume either is on `main` or the most recently created branch.
- If sources disagree or unmerged changes might be lost, stop and ask; never reset, force-push, delete, or silently discard work.
- Report the verified base branch and commit before implementation, and update the handoff with actual evidence when done.

## Mandatory APK delivery (shared standard)
An Android assignment is finished only when a tappable APK is attached to the chat (`SendUserFile`), labelled as a CI artifact or
a local build, with repository, branch, source commit, CI run and validation stated. The authoritative standard is
https://github.com/mrmundysr-sudo/touch-development/blob/main/autobuild/APK-DELIVERY.md ; settings for this app live in
`.autobuild/deliver.conf`; run the hub's `autobuild/deliver/deliver_apk.sh --repo . --commit <sha>`. Onboard the repository to
Touch AutoBuild first (`autobuild/ONBOARDING.md` in the same hub). Never claim an attachment the tool did not confirm.

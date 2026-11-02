# [App Name] — Development Plan

> Status: Not approved. Plan only after inspecting PRODUCT_VISION.md and DECISIONS.md. No implementation without explicit approval.

## Source of truth and working rules
- GitHub repository is the source of truth.
- Verify repo, default branch, active working branch and workflow before edits.
- Develop on a feature/development branch. Do not merge to main, release publicly, or modify other repositories without approval.
- Use GitHub Actions to build/test and inspect actual results. Do not claim an APK is ready without verified success.
- Protect existing working behavior; prefer small, reviewable changes.
- Do not invent requirements or interpret brainstormed ideas as approved decisions.

## Feasibility audit
| Question | Evidence needed | Result |
|---|---|---|
| Existing reusable code | Repo paths and versions | Pending |
| Platform constraints | Android/API/device tests | Pending |
| Dependencies/licenses | License review | Pending |
| Performance | Device benchmarks | Pending |
| Security/privacy | Threat and data-flow review | Pending |

## Proposed architecture
- Components and boundaries: [pending]
- Shared modules and adapters: [pending]
- Data model, storage, migration: [pending]
- Offline/network strategy: [pending]
- Permissions/security: [pending]
- Build and CI workflows: [pending]

## Milestones
### Phase 0 — Audit and design
- [ ] Review approved requirements and existing code
- [ ] Document risks, dependencies, unknowns
- [ ] Obtain approval for architecture and MVP

### Phase 1 — Minimal working application
- [ ] [Approved tasks only]
- [ ] Unit/integration tests
- [ ] Verified CI build and test APK

### Phase 2 — Usability and reliability
- [ ] [Approved tasks only]
- [ ] Real-device tests, failure recovery, accessibility

### Phase 3 — Release readiness
- [ ] [Approved tasks only]
- [ ] Privacy, licenses, crash handling, versioning, rollback

## Definition of done
- Approved acceptance criteria pass.
- Tests and GitHub Actions are green.
- APK artifact or private release is verified.
- Known limitations and manual testing recorded.
- No unapproved merges/releases.

## Risks and open questions
- [Risk, impact, mitigation]

## Approval
- **Development authorized:** No
- **Approved scope / date:** [pending]

## Knowledge retention (template-wide requirement)
- [ ] Before each authorized assignment, review relevant docs/knowledge/ references.
- [ ] After each substantial assignment or batch, write a dated Markdown report using docs/knowledge/WORK_REPORT_TEMPLATE.md.
- [ ] Include user punch lists, fixes, actual CI/test evidence, and outstanding issues.
- [ ] Promote verified reusable lessons/patterns to shared project knowledge files.
- [ ] Propose cross-project lessons for owner review; do not edit other repositories without approval.

## Component reuse gate
- [ ] Before implementing, search the studio registry and inspect relevant source.
- [ ] Prefer verified compatible reusable components; document exceptions.
- [ ] After testing, propose additions/updates to the registry with source paths and evidence.
- [ ] Obtain explicit approval for cross-repository extraction or modifications.

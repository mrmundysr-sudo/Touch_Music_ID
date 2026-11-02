# Shared Component Registry — Mandatory Discovery

**Authoritative studio catalog:** https://github.com/mrmundysr-sudo/Touch-Modules/tree/main/registry
**Machine-readable index:** https://github.com/mrmundysr-sudo/Touch-Modules/blob/main/registry/components.json

Before implementing any substantial new UI, game mechanic, media feature, networking, persistence, or build service:
1. Read the registry index and relevant component docs.
2. Check source code at the recorded repository/path/revision; inspect interfaces, dependencies, licensing, compatibility, limitations and tests.
3. Prefer a compatible **reusable** component; adapt with care. A component only tested in its source app requires extra integration validation.
4. If unsuitable, document why in the assignment work report. Do not duplicate blindly.
5. After work, identify candidate reusable components, and propose registry additions with evidence. Never assert reusable status without verification in another app.
6. Do not make cross-repository changes, extract shared modules, or publish releases without explicit authorization.

The central index is a catalog, not proof the code has been packaged as a library. Source repositories remain the source of truth for their code.

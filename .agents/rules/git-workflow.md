---
trigger: always_on
---

# Git Branching & Release Workflow

This repository strictly enforces a multi-tier branching strategy:

1. **`main` Branch (Production Releases Only)**:
   - Reserved exclusively for official, tagged, production releases (e.g. `v1.0.0`, `v1.1.0`).
   - Direct development commits to `main` are strictly prohibited.
   - Code is only merged into `main` from `dev` when an official release build is validated and ready for publishing.

2. **`dev` Branch (Staging & Integration)**:
   - Acts as the central integration branch for completed and verified features.
   - Feature branches merge into `dev` only after full build verification (`./gradlew.bat test` and `./gradlew.bat assembleRelease`).

3. **`feature/*` Branches (Active Feature Development)**:
   - All active development, refactors, and bug fixes must occur on dedicated feature branches (e.g., `feature/m9-foreground-service-recovery`, `feature/m10-uibc-touch`).
   - Feature branches branch off from `dev`.

4. **Release Lifecycle**:
   - Step 1: Work on `feature/<name>` branch -> Run `./gradlew.bat test` & `./gradlew.bat assembleDebug`.
   - Step 2: Push feature branch -> Merge into `dev`.
   - Step 3: Verify integration on `dev` -> Run `./gradlew.bat assembleRelease`.
   - Step 4: Merge `dev` into `main` -> Tag version (`git tag -a vX.Y.Z`) -> Publish release.

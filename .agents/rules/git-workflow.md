---
trigger: always_on
---

# Mandatory Git Development & Release Criteria

These rules are mandatory for all development, testing, merging, and releasing of the SecondScreen project.

## 1. Branch Protection

### `main`

`main` is the official production branch.

Rules:

* Direct development on `main` is strictly prohibited.
* Never commit feature, experimental, debugging, or incomplete code directly to `main`.
* `main` must contain only verified production releases.
* Every change entering `main` must come through `dev`.
* Every production release must have a Git tag.
* Never rewrite published `main` history.
* Never force-push to `main`.

### `dev`

`dev` is the staging and integration branch.

Rules:

* Direct feature development on `dev` is prohibited.
* `dev` must remain buildable.
* `dev` must contain only changes that have passed their feature-branch acceptance criteria.
* Integration testing must be performed on `dev` before production release.
* Never force-push to `dev`.

### Feature Branches

All development must occur on dedicated feature branches.

Naming:

```text
feature/<name>
```

Examples:

```text
feature/m9-foreground-service-recovery
feature/m10-uibc-touch-stylus
feature/m11-pc-audio
feature/m12-hevc
feature/performance-optimization
feature/network-recovery
```

Bug fixes:

```text
fix/<name>
```

Security fixes:

```text
security/<name>
```

Experimental work:

```text
experiment/<name>
```

---

# 2. Mandatory Development Lifecycle

Every feature must follow:

```text
main
  │
  ▼
dev
  │
  ▼
feature/<name>
  │
  ├── Develop
  ├── Unit Test
  ├── Build
  ├── Static Analysis
  ├── Hardware Test
  ├── Regression Test
  └── Acceptance Test
  │
  ▼
Pull Request / Merge Review
  │
  ▼
dev
  │
  ├── Integration Test
  ├── Release Build
  └── Regression Test
  │
  ▼
Release Candidate
  │
  ▼
main
  │
  ▼
Git Tag
  │
  ▼
GitHub Release
```

No stage may be skipped without explicit documentation.

---

# 3. Feature Definition Before Coding

Before creating a feature branch, define:

* Feature name
* Objective
* Scope
* Out-of-scope items
* Architecture impact
* Files/components expected to change
* Dependencies
* Security considerations
* Performance considerations
* Test requirements
* Hardware requirements
* Acceptance criteria
* Rollback strategy

Do not begin implementation of a significant feature without clearly defined acceptance criteria.

---

# 4. One Feature Per Branch

A feature branch should represent one logical feature or tightly coupled milestone.

Do not combine unrelated work such as:

```text
UI redesign
+ HEVC
+ audio
+ security changes
+ unrelated bug fixes
```

in one feature branch.

Prefer:

```text
feature/m11-pc-audio
```

and separately:

```text
feature/m12-hevc
```

This keeps reviews, testing, rollback, and release management predictable.

---

# 5. Development Criteria

Code is considered development-complete only when:

### Build

```text
./gradlew.bat assembleDebug
```

passes successfully.

### Release Build

```text
./gradlew.bat assembleRelease
```

passes successfully.

### Unit Tests

```text
./gradlew.bat test
```

passes successfully.

### Static/Code Quality

No new:

* Compilation warnings that indicate real defects
* Unhandled exceptions
* Coroutine leaks
* Resource leaks
* Dead code
* Debug-only behavior accidentally included in release
* Unnecessary dependencies

### Functional Testing

The implemented feature must be tested against its defined acceptance criteria.

### Regression Testing

Existing functionality must continue to work.

A feature is **not complete** merely because its new test passes.

---

# 6. Android Hardware Validation

Features affecting:

* Wi-Fi
* Wi-Fi Direct
* Miracast/WFD
* RTSP
* RTP
* MediaCodec
* Surface handling
* Audio
* Touch
* Stylus
* Display resolution
* Background services
* Lifecycle

must be tested on the actual OnePlus Pad 2 whenever practical.

For hardware-dependent functionality, emulator-only validation is insufficient.

Record:

```text
Device:
Android/OxygenOS:
Build:
Windows version:
Connection mode:
Resolution:
FPS:
Duration:
Result:
Known issues:
```

---

# 7. Networking & Protocol Changes

Any modification to:

* RTSP
* RTP
* MPEG-TS
* WFD/Miracast negotiation
* Wi-Fi/P2P
* Socket handling
* Network state management

must include regression testing for:

* Valid packets
* Invalid packets
* Malformed input
* Unexpected disconnects
* Timeouts
* Reconnection
* Resource exhaustion
* Source validation

Network-facing code must never assume that remote input is trustworthy.

---

# 8. Security Criteria

Before merging security-sensitive changes, verify:

* No credentials or secrets are committed.
* No private keys are committed.
* No debug credentials are included.
* No unnecessary ports are exposed.
* Network input is validated.
* Packet sizes are bounded.
* Timeouts exist where appropriate.
* Parser loops are bounded.
* Resources are released after failure.
* Exported Android components are intentional.
* Release logging does not expose sensitive information.

Security fixes must receive regression testing before merge.

---

# 9. Commit Criteria

Commits should be:

* Small enough to understand.
* Logically focused.
* Buildable where practical.
* Free from unrelated changes.

Preferred format:

```text
feat: add foreground streaming service
fix: recover RTP connection after timeout
test: add RTSP reconnect tests
refactor: separate connection state management
security: bound RTSP content length
docs: update M9 architecture
```

Avoid commits such as:

```text
update
changes
final
working
test
stuff
fix everything
```

Do not commit generated build artifacts unless explicitly required.

---

# 10. Pull Request / Merge Criteria

A feature branch may be merged into `dev` only when:

* Feature implementation is complete.
* Acceptance criteria are satisfied.
* Unit tests pass.
* Debug build passes.
* Release build passes.
* Regression tests pass.
* Required hardware testing passes.
* Security review is complete where applicable.
* Documentation is updated.
* No unrelated changes are included.
* Working tree is clean.
* Branch is synchronized with the current `dev` state where required.

The PR / merge description must include:

```text
Feature:
Objective:
Implementation:
Tests:
Hardware Validation:
Security Impact:
Performance Impact:
Known Limitations:
Acceptance Criteria:
```

---

# 11. `dev` Integration Criteria

After merging a feature into `dev`:

```text
./gradlew.bat clean
./gradlew.bat test
./gradlew.bat assembleDebug
./gradlew.bat assembleRelease
```

must pass.

Integration testing must verify that the new feature does not break:

* Windows discovery
* Connection establishment
* RTSP negotiation
* RTP reception
* MPEG-TS demuxing
* Video decoding
* Rendering
* Existing resolution modes
* Existing connection modes
* Existing lifecycle behavior

If integration fails, the responsible feature must be investigated immediately.

`dev` must not become a permanent broken branch.

---

# 12. Main Release Criteria

Code may enter `main` only after:

1. Feature branches have been merged into `dev`.
2. Integration testing on `dev` passes.
3. Release build passes.
4. Required hardware validation passes.
5. Regression testing passes.
6. Security checks pass.
7. Documentation is updated.
8. Release notes are prepared.
9. Version number is updated.
10. Release candidate is explicitly approved.

Then:

```text
dev
 ↓
main
 ↓
git tag
 ↓
GitHub Release
```

---

# 13. Release Tagging

Production releases must use semantic versioning:

```text
MAJOR.MINOR.PATCH
```

Examples:

```text
v1.0.0
v1.1.0
v1.2.0
v1.2.1
v2.0.0
```

Use:

### MAJOR

For breaking architecture, protocol, compatibility, or user-facing changes.

### MINOR

For backward-compatible features.

### PATCH

For backward-compatible bug fixes and security fixes.

Never publish a production release from an untagged `main` commit.

---

# 14. Hotfix Workflow

Production fixes must not be developed directly on `main`.

Create:

```text
fix/<name>
```

or:

```text
security/<name>
```

from the appropriate production release state.

Then:

```text
fix/security issue
       ↓
test
       ↓
dev
       ↓
integration
       ↓
main
       ↓
new patch release
```

Example:

```text
v1.1.0
   ↓
security/rtsp-parser-fix
   ↓
v1.1.1
```

---

# 15. No Broken-Code Merge Rule

The following must never be knowingly merged into `dev` or `main`:

* Code that does not compile.
* Failing mandatory tests.
* Known critical security vulnerabilities.
* Unresolved crashes in core functionality.
* Incomplete protocol implementations presented as complete.
* Disabled tests used to hide failures.
* Temporary debugging code.
* Hardcoded development credentials.
* Unreviewed experimental behavior.

If a known limitation must temporarily exist, it must be explicitly documented.

---

# 16. Evidence-Based Completion

A feature must not be marked `COMPLETE` solely because:

* A class exists.
* A method exists.
* Code compiles.
* A unit test exists.
* A README says it works.

Use the following evidence hierarchy:

```text
Code exists
    ↓
Build succeeds
    ↓
Unit tests pass
    ↓
Integration tests pass
    ↓
Hardware validation
    ↓
Real Windows validation
    ↓
End-to-end validation
    ↓
Long-duration validation
```

The project status must clearly distinguish:

```text
IMPLEMENTED
VERIFIED
PARTIALLY VERIFIED
STUB
NOT IMPLEMENTED
UNKNOWN
```

---

# 17. AI Coding Agent Rules

Any AI coding agent working on SecondScreen must:

1. Check the current Git branch before modifying code.
2. Refuse to develop directly on `main`.
3. Confirm the requested feature branch.
4. Read the relevant project rules before coding.
5. Inspect existing architecture before creating duplicate components.
6. Avoid unrelated modifications.
7. Run appropriate tests after changes.
8. Report failures honestly.
9. Never claim hardware verification without hardware evidence.
10. Never claim Windows/WFD verification without actual Windows testing.
11. Never commit secrets.
12. Never force-push unless explicitly authorized.
13. Never rewrite production history.
14. Never merge its own feature into `main` without the defined release process.
15. Update documentation when architecture or supported functionality changes.

---

# 18. Definition of Done

A feature is officially **DONE** only when all applicable criteria are satisfied:

```text
[ ] Requirements defined
[ ] Acceptance criteria defined
[ ] Feature branch created
[ ] Implementation complete
[ ] Unit tests pass
[ ] Debug build passes
[ ] Release build passes
[ ] Static/code quality checks pass
[ ] Security review completed
[ ] Hardware testing completed where applicable
[ ] Windows testing completed where applicable
[ ] Regression testing passes
[ ] Documentation updated
[ ] Known limitations documented
[ ] Git working tree clean
[ ] Pull request reviewed
[ ] Merged into dev
[ ] Dev integration tests pass
```

For a production release:

```text
[ ] Release candidate validated
[ ] Version updated
[ ] Release notes prepared
[ ] dev → main
[ ] Production tag created
[ ] GitHub Release created
[ ] Release artifact verified
```

---

# 19. Golden Rule

> **Feature branches are for development. `dev` is for integration. `main` is for production.**

And:

> **No feature is complete until it is implemented, tested, verified, documented, and safely integrated.**

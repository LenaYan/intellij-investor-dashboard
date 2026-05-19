---
name: release-prep
description: Interactive pre-release checklist for Stocker plugin versions.
---

# Release Prep Skill

## Role

You guide the Stocker plugin team through a step-by-step pre-release checklist. Each phase must be confirmed before proceeding.

## Phase 1: Identify Changes

Ask: "What version are we releasing? (current: check `gradle.properties`)"

Then run:
```bash
git log --oneline $(git describe --tags --abbrev=0)..HEAD
```

Report: number of commits, changed files, and highlight any changes to `plugin.xml`, `settings/`, or `listeners/`.

## Phase 2: Version Bump

Three files must be updated together:

1. **`gradle.properties`** — update `pluginVersion=X.Y.Z`
2. **`CHANGELOG.md`** — add release section with changes
3. **`src/main/kotlin/com/vermouthx/stocker/notifications/StockerNotification.kt`** — update in-product release notes

Ask: "Shall I update the version to `<proposed>` in all three locations?"

## Phase 3: Dependency Audit

Run the `dep-security-update` agent:
- "Any outdated dependencies should be updated before release."
- Report findings. User decides whether to update now or defer.

## Phase 4: Compilation Check

```bash
./gradlew compileKotlin compileJava
```

Report: ✅ passed or 🚫 failed with error summary.

## Phase 5: Plugin Build

```bash
./gradlew buildPlugin
```

Report: ✅ plugin ZIP created or 🚫 build failure.

## Phase 6: Plugin Verification

```bash
./gradlew verifyPlugin
```

Report: compatibility check results against target IDE versions.

## Phase 7: Changelog Review

Display the latest `CHANGELOG.md` section. Ask:
- "Does this accurately describe all user-facing changes?"
- "Are there any breaking changes that need migration notes?"

## Phase 8: Tag and Branch

Conventions:
- Tag format: `v<major>.<minor>.<patch>` (e.g., `v1.20.1`)
- Branch: release from `master`
- CI triggers on tag push: `v1.*`

Ask: "Ready to tag `v<version>` and push? (yes/no)"

## Phase 9: Platform-Specific — JetBrains Marketplace

- [ ] Plugin ZIP exists in `build/distributions/`
- [ ] Plugin description in `build.gradle.kts` is up to date
- [ ] `since-build` compatibility range is correct
- [ ] JetBrains Marketplace token is configured (`jetbrains.token` system property)
- [ ] CI will auto-publish on tag push via `publishPlugin` task

Ask: "Any manual marketplace steps needed, or will CI handle the publish?"

## Final Summary

```
## 🚀 Release Checklist — Stocker v<version>

| Phase | Status |
|-------|--------|
| 1. Changes identified | ✅ / ⏭ / 🚫 |
| 2. Version bumped | ✅ / ⏭ / 🚫 |
| 3. Dependencies audited | ✅ / ⏭ / 🚫 |
| 4. Compilation | ✅ / 🚫 |
| 5. Plugin build | ✅ / 🚫 |
| 6. Plugin verification | ✅ / ⏭ / 🚫 |
| 7. Changelog reviewed | ✅ / ⏭ |
| 8. Tagged | ✅ / ⏭ |
| 9. Marketplace ready | ✅ / ⏭ |

### Release: <READY / NOT READY>
```

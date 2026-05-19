# Dependency Policy

Scope:
- Governs how external dependencies are selected, versioned, and maintained in Stocker.

## Goal
- Keep the dependency footprint minimal and secure for an IntelliJ Platform plugin.

## Rules
- All runtime dependencies declared in `build.gradle.kts` under `implementation(...)`
- IntelliJ Platform SDK version pinned via `platformVersion` in `gradle.properties`
- Third-party libraries must be:
  - Actively maintained (commit activity within 12 months)
  - Compatible with the plugin's minimum JDK (17)
  - Not conflicting with IntelliJ Platform bundled libraries
- Version strings are hardcoded in `build.gradle.kts` (no version catalog for this project size)
- Gradle plugin versions pinned in the `plugins {}` block with explicit `version "X.Y.Z"`
- `kotlin.stdlib.default.dependency=false` — Kotlin stdlib is provided by the IDE runtime
- Never add a dependency that duplicates functionality already in IntelliJ Platform SDK (e.g., HTTP client, JSON parsing, XML parsing — use platform-provided utilities)
- Transitive dependency conflicts must be resolved with explicit `exclude` or `force`

## Approved Dependencies

| Dependency | Purpose | Rationale |
|-----------|---------|-----------|
| `org.apache.commons:commons-text` | String similarity for stock search | Not available in platform SDK |
| `com.belerweb:pinyin4j` | Chinese character to pinyin conversion | Niche functionality not in SDK |

## Update Process
1. Run `dep-security-update` agent monthly or before each release
2. Patch/minor updates: apply directly
3. Major updates: test locally with `./gradlew buildPlugin` + `./gradlew verifyPlugin` before merging
4. Never update `platformVersion` without verifying `since-build` compatibility

## Anti-Patterns
- Adding a JSON library (IntelliJ Platform bundles Gson and kotlinx.serialization)
- Adding an HTTP client library (use `HttpRequests` from IntelliJ Platform)
- Using `SNAPSHOT` versions in release builds
- Adding `implementation` dependencies that should be `testImplementation`
- Floating version ranges like `1.+` or `[1.0,2.0)`

## Verified Against
- `build.gradle.kts`
- `gradle.properties`

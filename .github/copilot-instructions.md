# GitHub Copilot Instructions for Stocker

## Hard Rules

**NEVER**
- Do not use non-English identifiers or comments in code
- Do not use hardcoded user-visible strings — all UI text must go through `StockerBundle`
- Do not use raw `Thread()` or `Thread.sleep()` — use IntelliJ Platform threading APIs (`ApplicationManager`, `invokeLater`, coroutines)
- Do not add new Actions, Services, or Listeners without registering them in `META-INF/plugin.xml`

**File Placement**
- When generating a Markdown (`.md`) file as output (reports, analysis, work logs), default its location to the `ai_work_log/` directory in the repo root unless the user specifies otherwise.

## Available Agents

| Agent | File | Purpose |
|-------|------|---------|
| compliance-check | `.github/agents/compliance-check.agent.md` | Reviews code against domain contracts; reports violations |
| component-scaffold | `.github/agents/component-scaffold.agent.md` | Scaffolds new plugin components (Actions, Services, Tool Windows, Listeners) |
| test-coverage | `.github/agents/test-coverage.agent.md` | Identifies untested units and writes JUnit5/MockK tests |
| dep-security-update | `.github/agents/dep-security-update.agent.md` | Audits Gradle dependencies against Maven Central |
| domain-contract-scaffold | `.github/agents/domain-contract-scaffold.agent.md` | Creates new domain contract documents |

## Available Skills

| Skill | File | Purpose |
|-------|------|---------|
| architecture-advisor | `.github/skills/architecture-advisor/SKILL.md` | Decides where new components belong and which patterns to use |
| release-prep | `.github/skills/release-prep/SKILL.md` | Interactive pre-release checklist |

## How to Use

- Treat this file as the **entry index** only.
- Load only the domain file needed for the current task.
- Keep generation scoped; do not pull all domain contracts into every task.

## Domain Contracts

### Index Pages
- `common domains index` page: `.gitconfig/copilot/domains/common_domains/index.md`

### Architecture and Boundaries
- `naming-and-file-placement` domain (naming conventions and file locations): `.gitconfig/copilot/domains/common_domains/naming-and-file-placement.md`
- `module-boundaries` domain (layer and package dependency rules): `.gitconfig/copilot/domains/common_domains/module-boundaries.md`

### Async and Concurrency
- `async-patterns` domain (threading and background task rules): `.gitconfig/copilot/domains/common_domains/async-patterns.md`

### Error Handling
- `error-handling` domain (error propagation and user feedback): `.gitconfig/copilot/domains/common_domains/error-handling.md`

### UI / State Management
- `ui-state-patterns` domain (tool windows, tables, message bus): `.gitconfig/copilot/domains/common_domains/ui-state-patterns.md`

### Data and Network
- `service-api-surface-and-boundaries` domain (HTTP clients and quote providers): `.gitconfig/copilot/domains/common_domains/service-api-surface-and-boundaries.md`

### Storage
- `storage-and-cache` domain (persistent settings and data caching): `.gitconfig/copilot/domains/common_domains/storage-and-cache.md`

### Testing
- `testing-defaults` domain (test framework and conventions): `.gitconfig/copilot/domains/common_domains/testing-defaults.md`

### Dependencies
- `dependency-policy` domain (approved libraries and version management): `.gitconfig/copilot/domains/common_domains/dependency-policy.md`

### Governance
- `domain-contract-maintenance-checklist` domain (when and how to update contracts): `.gitconfig/copilot/domains/common_domains/domain-contract-maintenance-checklist.md`

## Contract Reading Rules
- Prefer existing shared components over reinvention.
- When behavior is marked as legacy/current, preserve it unless migration is explicit.
- Keep comments concise and in English.
- For behavior changes, update both source code and the corresponding domain contract.

## Maintenance Checklist
- Keep this index short; add detail to domain files.
- Add or update rules in domain files, then update links here.

# Testing Defaults

Scope:
- Defines the test framework, conventions, and expectations for the Stocker plugin.

## Goal
- Establish a consistent testing approach that works within IntelliJ Platform plugin constraints.

## Rules
- Test framework: JUnit 5 (Jupiter) for unit tests
- Mocking library: MockK for Kotlin, Mockito for Java (if needed)
- Test file naming: `Stocker<Subject>Test.kt` or `Stocker<Subject>Test.java`
- Test placement: mirror source structure under `src/test/kotlin/` or `src/test/java/`
- Test method naming: use backtick syntax for Kotlin — `` `should describe expected behavior` ``
- Each test class must have at least one happy-path test and one failure-case test
- Utilities and entity logic must be unit-tested without IDE dependencies
- Actions and services that depend on IntelliJ APIs should use `BasePlatformTestCase` or mock the platform
- Never hit real network endpoints in unit tests — mock HTTP responses
- Use `@BeforeEach` for shared setup; avoid `@BeforeAll` (test isolation)

## Test Categories

| Category | Framework | Dependencies |
|----------|-----------|--------------|
| Unit (utils, entities) | JUnit 5 + MockK | None |
| Integration (services) | JUnit 5 + IntelliJ test framework | `intellijPlatform { testFramework() }` |
| UI (actions, dialogs) | JUnit 5 + `BasePlatformTestCase` | Full IDE test harness |

## Anti-Patterns
- Tests that require a running IDE instance for pure logic validation
- Tests with no assertions (compilation-only tests)
- Tests that depend on network availability
- Test classes not following the `Stocker` prefix convention
- Using `Thread.sleep()` in tests instead of proper synchronization

## Verified Against
- To be filled in — see Maintenance Checklist (no test directory exists yet)

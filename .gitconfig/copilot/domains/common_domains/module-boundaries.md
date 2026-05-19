# Module Boundaries

Scope:
- Defines allowed dependencies between packages within the single-module Stocker plugin.

## Goal
- Prevent circular dependencies and maintain clear layering even within a single Gradle module.

## Rules
- **Layer ordering** (top depends on bottom, never reverse):
  ```
  actions / views / activities  (presentation layer)
       ↓
  services / StockerAppManager  (application layer)
       ↓
  utils / entities / enums      (core/domain layer)
  ```
- `entities` and `enums` packages must have ZERO imports from other Stocker packages
- `utils` may only import from `entities` and `enums`
- `listeners` may import from `entities`, `enums`, and `components` — never from `actions` or `views`
- `views` and `actions` may import from any lower layer
- `settings` is a cross-cutting concern — may be imported from any layer but must not import `views` or `actions`
- `StockerAppManager` is the single orchestrator — it coordinates between settings, services, and the message bus
- No package may import from `activities` (startup hooks are entry points only)

## Communication Across Layers

- Upward communication uses the IntelliJ **Message Bus** (`Topic` + `Notifier` + `Listener`)
- Never pass a view/component reference downward into utils or entities
- Services expose data; views subscribe to message-bus events to refresh

## Anti-Patterns
- A utility class importing an Action or Dialog class
- An entity holding a reference to a Swing component
- Direct method calls from a listener into a view (use message bus instead)
- Circular imports between `actions` and `views`
- `StockerAppManager` importing UI-specific classes

## Verified Against
- `src/main/kotlin/com/vermouthx/stocker/StockerAppManager.kt`
- `src/main/java/com/vermouthx/stocker/listeners/StockerQuoteUpdateNotifier.java`

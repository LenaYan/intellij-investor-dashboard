# Naming and File Placement

Scope:
- Governs naming conventions for classes, files, packages, and resources in the Stocker plugin.

## Goal
- Maintain consistent, discoverable naming across the mixed Kotlin/Java codebase.

## Rules
- All public classes in `com.vermouthx.stocker` must use the `Stocker` prefix (e.g., `StockerRefreshAction`, `StockerTableModel`)
- Class name must match file name exactly (enforced by both Kotlin and Java compilers)
- Package names are singular lowercase: `actions`, `entities`, `enums`, `utils`, `views`, `settings`, `listeners`, `notifications`, `activities`
- Action classes end with `Action` suffix: `Stocker<Feature>Action`
- Listener interfaces end with `Listener` suffix; notifier interfaces end with `Notifier` suffix
- Entity/model classes use noun names without suffix: `StockerQuote`, `StockerMarketIndex`
- Enum classes end with their domain concept: `StockerMarketType`, `StockerQuoteColorPattern`
- Utility classes end with `Util` suffix: `StockerPinyinUtil`, `StockerQuoteHttpUtil`
- Resource bundle keys use dot-separated lowercase: `stocker.action.refresh.text`
- Icon files use lowercase-kebab-case: `stocker-logo.svg`

## File Placement

| Type | Kotlin path | Java path |
|------|-------------|-----------|
| Actions | `src/main/kotlin/.../actions/` | — |
| Services | `src/main/kotlin/.../services/` | — |
| Entities | `src/main/kotlin/.../entities/` | — |
| Enums | `src/main/kotlin/.../enums/` | `src/main/java/.../enums/` |
| Views/Dialogs | `src/main/kotlin/.../views/dialogs/` | — |
| Tool Windows | `src/main/kotlin/.../views/windows/` | — |
| Table Components | — | `src/main/java/.../components/` |
| Table Views | — | `src/main/java/.../views/` |
| Listeners | — | `src/main/java/.../listeners/` |
| Utilities | `src/main/kotlin/.../utils/` | `src/main/java/.../utils/` |
| Bundle | `src/main/resources/messages/` | — |
| Icons | `src/main/resources/icons/` | — |

## Anti-Patterns
- Creating a class without the `Stocker` prefix (breaks discoverability and grep-ability)
- Placing Swing table code in Kotlin (existing convention keeps it in Java)
- Using `Manager`, `Helper`, or `Handler` suffix when `Util` or `Service` is more specific
- Placing a listener in the Kotlin source tree (existing convention keeps listeners in Java)
- Using camelCase for resource bundle keys (use dot-separated lowercase)

## Verified Against
- `src/main/kotlin/com/vermouthx/stocker/actions/StockerRefreshAction.kt`
- `src/main/java/com/vermouthx/stocker/components/StockerTableModel.java`
- `src/main/resources/messages/StockerBundle.properties`

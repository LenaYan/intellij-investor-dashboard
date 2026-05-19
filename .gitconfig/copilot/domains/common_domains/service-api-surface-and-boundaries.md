# Service API Surface and Boundaries

Scope:
- Governs how HTTP clients fetch market data and how quote providers are abstracted.

## Goal
- Clean separation between HTTP transport, response parsing, and business logic.

## Rules
- HTTP requests use `StockerHttpClientPool` for connection management — never create raw connections
- Quote fetching is split into two concerns:
  1. `StockerQuoteHttpUtil` — makes HTTP requests and returns raw response strings
  2. `StockerQuoteParser` — parses raw responses into `StockerQuote` entities
- Suggestion/search fetching uses `StockerSuggestHttpUtil` — separate from quote fetching
- All HTTP utilities are stateless — they receive parameters and return results
- Quote providers are enumerated in `StockerQuoteProvider` — switching providers changes the URL template only
- Request timeouts must be configured (not infinite) — fail fast on unresponsive endpoints
- Response parsing must handle:
  - Empty response body → return empty list
  - Malformed data → log and return empty list
  - Partial data (some fields missing) → use sensible defaults
- Never expose raw HTTP response objects outside `utils/` package

## API Contract

```
StockerQuoteHttpUtil.get(codes: List<String>, provider: StockerQuoteProvider) → String (raw)
StockerQuoteParser.parse(raw: String, provider: StockerQuoteProvider) → List<StockerQuote>
StockerSuggestHttpUtil.suggest(keyword: String) → List<StockerSuggestItem>
```

## Anti-Patterns
- Parsing HTTP responses inside an Action or View class
- Hardcoding API URLs outside of the provider enum/utility
- Making HTTP calls synchronously on EDT
- Returning `null` from HTTP utilities instead of empty string/list
- Caching responses inside HTTP utility classes (caching belongs in a service layer)

## Verified Against
- `src/main/kotlin/com/vermouthx/stocker/utils/StockerQuoteHttpUtil.kt`
- `src/main/kotlin/com/vermouthx/stocker/utils/StockerQuoteParser.kt`
- `src/main/kotlin/com/vermouthx/stocker/utils/StockerSuggestHttpUtil.kt`

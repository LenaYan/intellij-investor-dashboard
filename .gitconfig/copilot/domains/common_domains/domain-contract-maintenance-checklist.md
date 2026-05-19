# Domain Contract Maintenance Checklist

Scope:
- Governs when and how domain contracts are updated, reviewed, and exceptions are tracked.

## Goal
- Keep domain contracts accurate and aligned with the actual Stocker codebase.

## When to Update a Contract

- A new pattern is introduced that contradicts or extends an existing rule
- An existing pattern is deprecated or replaced
- A new package, module, or architectural layer is added
- A compliance check reveals a rule that is consistently violated (rule may need revision)
- A dependency is added or removed

## Review Process

1. Author proposes change in a PR that modifies both source code and the affected domain contract
2. Reviewer verifies the contract change matches the code change
3. Entry index (`.github/copilot-instructions.md`) is updated if a new domain is added or one is removed
4. Domain index (`common_domains/index.md`) is updated accordingly

## Exception Approval

When a rule must be violated for a justified reason:

1. Document the exception in the Active Exception Register below
2. Add a code comment referencing the Exception ID: `// Exception EX-001: <brief reason>`
3. Set an expiry date — exceptions should not be permanent unless truly necessary

## Exception Record Template

```
Exception ID: EX-<NNN>
Contract: <domain-name>
Rule violated: <rule text>
Reason: <justification>
Expires: <date or "permanent">
Approved by: <name>
```

## Active Exception Register

| ID | Contract | Rule | Reason | Expires |
|----|----------|------|--------|---------|
| — | — | — | No active exceptions | — |

## Anti-Patterns
- Changing code without updating the relevant domain contract
- Adding a domain contract that describes aspirational rules not yet enforced
- Letting exceptions accumulate without review (quarterly cleanup recommended)
- Making a domain contract so restrictive that it requires constant exceptions

## Verified Against
- This is a governance document — verified against the process itself.

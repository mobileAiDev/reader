# Source Search Waterfall and Book Identity Plan

## Goals

- Make source-engine novel search use a clear tier waterfall: tier1, then tier2, then tier3.
- Publish the first visible result as soon as a small trusted consensus is validated.
- Never end with "no result" until all candidates produced by tier1, tier2, and tier3 have been validated enough to prove that no publishable result exists.
- Keep expensive full ranking, tail probing, cover refresh, and content-tier fill out of the first-publish path.
- Use one source-engine book identity model for search grouping, search display merging, reading source fallback, and bookshelf duplicate merging.

## Tier Waterfall

Search starts with tier1 only.

1. Launch tier1 search requests.
2. Collect tier1 results as they arrive.
3. Trigger first-publish validation whenever a group reaches the validation threshold.
4. At tier1 search timeout, cancel unfinished tier1 search requests.
5. Keep validations already started from tier1 candidates.
6. If no publishable result has appeared after the tier1 ready-validation pass, start tier2.
7. Repeat the same flow for tier2.
8. Only start tier3 after tier1 and tier2 have failed to produce a publishable result.
9. Only report no data after tier1, tier2, and tier3 have all produced no publishable validated result.

There is no 5-second soft start for tier2. The flow is intentionally direct and easy to reason about.

## Time Budgets

- tier1 search timeout: 10 seconds.
- tier2 search timeout: 60 seconds.
- tier3 search timeout: 60 seconds, with the existing global cap as the final guard.
- First-publish validation is small batch and bounded.
- Full validation can continue after first publish, but it must not block the already visible result.

## Validation Trigger Thresholds

Validation trigger and publish eligibility are separate.

Trigger validation when:

- Exact normalized title plus compatible author has at least 2 independent sources.
- Exact normalized title with missing or anonymous author has at least 2 independent sources, but it must prefer a named-author candidate if validation discovers one.
- Related or contained title groups can be validated, but they must not stop the waterfall while an exact title group is still possible.

Independent source means distinct source URL, not result count.

For large exact groups, first-publish validation should use a small batch first:

- Validate the best 4 candidates.
- If the group is still not publishable but raw consensus is strong, validate another small batch.
- Full validation is reserved for final/background ranking, not first publish, and it must not stop at a fixed 16-candidate sample before reporting no data.

## Publish Eligibility

A search result can be published when the merged group has:

- A non-empty normalized title.
- A non-anonymous author after identity merge.
- A resolved readable catalog.
- A non-page catalog.
- At least 2 trusted independent source candidates for the same source-engine book identity.

Catalog rules:

- 2-source consensus can publish when at least one candidate has a long catalog.
- 2-source short-catalog consensus must not publish.
- 4-source short-catalog consensus can publish when catalog heads are roughly similar.

## Source-Engine Book Identity

Normalize title and author by removing symbols, punctuation, and whitespace.

Two candidates are the same source-engine book when any of these rules match:

1. Normalized title and normalized author are equal.
2. Normalized title is equal, and one author contains the other.
3. Normalized title is equal, and one author is anonymous.
4. Normalized titles are contained in either direction, and validated catalog heads are similar enough.

When same-title candidates are merged:

- Prefer the longer non-anonymous author.
- Treat anonymous author as weaker metadata.
- Keep route and readable catalog from the best readable candidate.
- Keep cover and intro from the best trusted metadata candidate.

Examples:

- `凡人修仙传 / 忘语` and `凡人修仙传 / 佚名` are the same book. Display author: `忘语`.
- `凡人修仙传 / 忘语` and `凡人修仙传 / 忘语著` are the same book. Display author: the longer named author.
- `凡人修仙传` and `凡人修仙传仙界篇`, both by `忘语`, are not the same book because catalog heads are not similar.
- `灵源仙路` aliases with contained titles and inconsistent or anonymous authors are the same book when catalog heads match.

## Search, Reading, and Bookshelf Scope

Search page:

- Use source-engine book identity to group and merge visible results.
- Related titles may remain visible, but exact-title groups sort first for exact user queries.
- Background updates may upgrade metadata or routes, but must not clear already visible results.

Reading page:

- Use the canonical route selected by the validated group.
- Reading source fallback should use the same identity rules when deciding whether a candidate is the same book.

Bookshelf:

- Source-engine shelf identity should use the canonical source-engine identity, not raw route ID.
- Duplicate source-engine shelf entries should merge by the same identity rules when available.
- If catalog evidence is not available in shelf-only data, use the conservative same-title author containment or anonymous-author rules. Do not merge contained titles without catalog evidence.

## Regression Coverage

Required tests:

- tier2 does not start before tier1 search timeout when tier1 has no publishable result.
- tier1 unfinished search requests are cancelled at timeout.
- tier1-started validations are kept after tier1 search cancellation.
- no-data is emitted only after tier1, tier2, and tier3 candidates are exhausted.
- same title plus anonymous author merges into named author.
- same title plus contained author merges and displays the longer author.
- contained titles with matching catalog heads merge.
- contained titles with different catalog heads stay separate.
- two-source short catalog still does not publish.
- two-source with one long catalog still publishes.
- four-source short catalog consensus still publishes.

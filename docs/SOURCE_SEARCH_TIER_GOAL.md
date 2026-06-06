# Source Search Tier Goal

Date: 2026-06-05

## Objective

Make source-engine search fast enough for first-screen display while preventing
short or low-quality catalogs from becoming the selected reading candidate.

The search list and the per-book content tier are separate surfaces:

```text
search first screen: choose what can be displayed quickly
per-book content tier: keep enough verified sources for stable reading
```

## Target Behavior

Search first screen:

- Run source search in strict tier waves: tier 1 settles first, then tier 2,
  then tier 3.
- Treat two-source consensus as provisional display evidence, not enough by
  itself to select a final reading source.
- Keep already-started validation candidates running after a provisional merge,
  so a better same-book candidate can replace a short-catalog candidate.
- Do not infer catalog quality from `lastChapter` text or kind metadata.
- If the validation batch has any same-book candidate with an actual catalog of
  at least 100 chapters, two readable sources can publish and longer readable
  catalogs should win ordering.
- If every same-book candidate is under 100 chapters, require four readable
  sources with a roughly matching catalog prefix before treating it as a reading
  candidate. Chapter counts may differ.
- Do not penalize newly published short books; they can publish through the
  four-source short-catalog consensus.

Per-book content tier:

- Keep collecting verified same-book sources after search/display.
- Target 32 verified sources for each book.
- Preserve the first-display threshold at 2 trusted sources so entering a book
  does not wait for the full per-book tier.
- Use source quality ordering to try better candidates first, while still
  allowing the broader pool to fill the tier.
- Keep recurring short-catalog evidence in source-quality generation. A source
  with two or more short-catalog samples is forced to tier 3; tier 1 requires no
  short-catalog samples.

## Non-Goals

- Do not run full V8 validation synchronously in the search first-screen path.
- Do not require a full same-book catalog distribution before displaying a new
  or short book.
- Do not reduce the broad per-book content tier to only the strict first-screen
  search tier.
- Do not pass a full multi-thousand-chapter catalog through `Intent` extras.
  Detail-to-read handoff must use a lightweight book payload plus the
  in-process source-engine session cache.

## Done Criteria

- The per-book content tier target is 32.
- The first-display trusted-source threshold remains 2.
- Search timing logs can separate source search, ranking, validation, network
  wait, and network execution.
- Short-catalog sources cannot permanently win when better same-book candidates
  are already being validated.
- New short books can still publish when four readable sources agree on the
  same catalog prefix.
- Opening a search result to the reader does not auto-add the book to the
  bookshelf. It may ask the user on exit, but cancelling must leave the shelf
  empty.
- Reader first-open can consume the detail/search session catalog without a
  large `Intent` payload.
- Unit or contract tests cover the changed constants and search/tier contracts.

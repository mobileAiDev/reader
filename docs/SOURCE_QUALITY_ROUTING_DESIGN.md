# Source Quality Routing Design

Date: 2026-05-20

## Goal

The reader should not search every source as the normal cold-start path. It
should start from a useful global source ranking, then adapt locally from real
user/runtime evidence.

The scoring model has two layers:

- Code-shipped seed: a global source profile stored in
  `app/src/main/assets/source-quality-seed-v1.tsv`.
- Local delta: MMKV-backed runtime increments for this installed app/user.

Single-book source scores are not seeded in code. They inherit the global source
score first, then diverge only after this user actually searches, opens,
validates, or rejects content for that book/source pair. Each book has one
personal tier, not personal tier 1/2/3. That personal tier is learned from
sources originally discovered through the global tier 1/2/3 waterfall.

## Seed File

Seed file format is TSV so it is diffable, easy to regenerate, and cheap to
load on Android:

```text
kind    sourceUrl   sourceName  tier    bucket  score   speed   coverage freshness quality stability note
source  https://... 55读书       1       general 8300    0       0        0         0       0         initial probe
```

Only `kind=source` is used in v1. The score is on a 0-10000 scale.

- `tier=1`: fast, broad, relatively fresh and high-quality sources. Used first.
- `tier=2`: breadth layer. Includes sources that are strong in categories not
  fully covered by tier 1, such as published works, romance, adult, and other
  verticals.
- `tier=3`: large fallback layer. Used for recall when tier 1/2 cannot produce a
  good result.
- `bucket`: used to interleave source categories inside a tier so a single class
  of fantasy/xuanhuan sites does not monopolize early requests.
- `score`: baseline source quality used by routing. Runtime MMKV delta is added
  on top.
- `speed/coverage/freshness/quality/stability`: reserved evidence columns for
  seed generation and review. Runtime routing only needs `tier`, `bucket`, and
  `score` in v1.

Cover availability is not a primary source-quality signal. A source may be fast,
fresh, complete, and readable while exposing no usable cover. Missing cover is a
small display penalty only; search/detail should keep the good reading source
and fill cover from tier 1 first, then tier 2/3 fallback sources. The user-facing
UI still must not show an empty cover.

The cover UX contract is stronger than the source-quality penalty. Search may
return quickly with an existing real cover or a generated title cover so the
first list is not blocked. After that, later source hits must continue trying to
find a real cover, for example when the third source or Nth source returns a
usable image. Once found, the real cover should update cache/UI and be reused by
detail and shelf. Missing cover should therefore be treated as a background
completion task, not as a reason to choose a worse reading source.

This file is intended to be periodically replaced by an offline/runtime probe
run. Users still keep their local MMKV deltas after app update; the new seed
becomes the new baseline and local behavior continues to adapt.

Seed regeneration entrypoint:

```powershell
node tools/source-quality/generate-source-quality-seed.mjs
```

Default inputs are `build/debug/device-book-sources.json` and the latest
100-book probe TSV under `build/tmp/`. The output overwrites the app asset.
The first committed v1 seed contains all currently compatible searchable
sources from the local dump, with 100-book evidence applied where available and
heuristic tiering for sources that have not yet been directly validated.

## Runtime Storage

Runtime storage uses `MMKV.mmkvWithID("source_quality_score_v1")` with a hot
in-memory cache and short write-behind buffering. It is designed for frequent
small updates from search/detail/catalog/content operations.

Stored records are local deltas, not the full seed copy:

- `source`: global source delta and evidence counters.
- `book_source`: per-book/per-source delta and latest observed/verified ordinal
  state.

Effective scores:

```text
SourceScore = seedScoreOrHeuristic + localSourceDelta
BookSourceScore = SourceScore + localBookSourceDelta * 2
```

That means a new book starts from the source's global quality. Once this exact
book/source pair has runtime evidence, successful catalog/content checks raise
the book-source score, while unreadable content, failed validation, catalog
disorder, and polluted tails lower it. A source enters this book's personal tier
only after it has enough positive book-specific evidence and verified readable
chapters. Repeated failures can lower the same book-source score until the
source is removed from that personal tier.

## Tail Pollution Rule

Wrong latest chapters are common on fast-updating sites, so tail pollution must
not heavily punish a source globally. The ranking keeps two ideas separate:

- `latestObservedOrdinal`: how far the source claims to have updated.
- `latestVerifiedGoodOrdinal`: how far we have verified readable content.
- `badTailStartOrdinal`: first known bad tail chapter, if detected.

When tail trimming happens, the score rewards newly verified readable chapters
and applies only a small penalty for a short bad tail:

```text
verifiedGain = keptOrdinal - previousVerifiedGoodOrdinal
badTailCount = rawChapterCount - keptOrdinal
bookDelta = verifiedGain * 20 - min(badTailCount * 4, 80)
sourceDelta = -1 for ordinary bad tail, larger only when nothing is readable
```

Therefore a source with 10 more observed chapters and 6 more verified readable
chapters can still beat a stale source, even if its last 4 chapters are bad. If
two sources are aligned to the same verified/latest range, the one with fewer
bad tail chapters ranks higher.

## Request Waterfall

Source routing uses one book-specific queue followed by the immutable global
waterfall:

1. Build this book's personal tier from source/book pairs with enough positive
   runtime evidence.
2. Sort that personal tier by `BookSourceScore`, highest first.
3. Consume the personal tier first.
4. Then consume the immutable global tier 1, tier 2, tier 3 waterfall, still
   sorted by global score and bucket interleaving inside each tier.

The global tier assignment is not rewritten by local learning. Local learning
only changes the membership and order of that one book's personal tier.

Adult content is not specially blocked by source routing or title filtering.
Fanfic/tongren remains demoted/filtered because it commonly represents a
different book contract, not merely a content category.

## Interaction Timing

Search is a fast path. It should not synchronously fetch detail pages, catalogs,
or chapter content. The first visible result list is based on:

- normalized title matching, with exact-title groups ranked ahead of derivative
  or longer containing-title groups;
- multi-source title consensus;
- global source seed plus local MMKV deltas;
- existing real cover, generated title cover, and short synchronous cover
  fallback only.

When the exact same title is seen from enough independent sources, search can
return immediately and cancel the remaining source jobs. If only derivative
titles have arrived, search must not stop the source jobs at the fast timeout;
it should continue the remaining search window so later exact-title candidates
can arrive and reorder the list. This is a generic rule, not a hard-coded
known-book rescue list.

Real-cover completion is allowed to continue after the first result list. The
important boundary is that real cover completion must happen eventually, but it
must not hold the entire search hostage.

Detail is a medium path. It should fetch enough book metadata and a catalog to
show the page, and the displayed latest chapter/count must use the same verified
readable-tail boundary as the reading catalog. The page may show its static
shell first, but once book metadata is rendered it should not advertise a tail
chapter that reading/catalog already rejects as unreadable.

Reading, adding to shelf, catalog opening, and content fallback are strong user
intent paths. These paths run the heavier catalog tail detection, content
belonging checks, and score updates. That is where per-book/per-source quality
should learn aggressively.

## Validation Plan

- Unit tests cover seed parsing, tier/bucket waterfall, MMKV-style persistence,
  adult vs fanfic behavior, and nuanced tail scoring.
- Batch engine probes cover the 100-book matrix across search, catalog, content,
  cover, and bad-tail handling.
- Batch status should not treat "only missing cover" as a source-quality
  failure. It should be reported as a separate cover-fallback/UI obligation.
- Real-device UI probes are required for representative high-risk books:
  `玄鉴仙族`, `我在修仙界万古长青`, `苟在妖武乱世修仙`, `第一序列`, and several
  broad category samples from the 100-book matrix.

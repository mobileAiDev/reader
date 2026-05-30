# Source Engine Iteration Plan

Date: 2026-05-30

## Active Work

The active source-engine chapter-integrity work is V8 PS-BMT. It is the only
production wrong-chapter detector in the current source-engine reading path.

Detailed documents:

- `docs/READING_V8_PSBMT_DESIGN.md`
- `docs/READING_V8_PSBMT_PROGRESS.md`

## Current Contract

V8 keeps catalog rows and attaches marks:

```text
NORMAL
WRONG
NON_STORY
BAD_EXTRACTION
INCONCLUSIVE
```

The catalog dialog can hide marked bad rows, but the stored catalog is not
mutated.

Catalog shape cleanup belongs to the source engine before app consumers see the
rows. Source sites often render a short latest-update block before the real
ascending catalog; the engine must drop that duplicated prefix during catalog
fusion instead of asking the bookshelf, reader, or V8 mark UI to compensate for
polluted input rows.

The reader drawer exposes wrong-chapter controls only after catalog analysis
evidence exists for the current source-engine book. Before a persisted or live
V8 catalog result is available, the drawer shows a lightweight analysis-in-
progress state instead of a `show wrong chapters` toggle that has no data behind
it.

## Scheduling

Foreground network requests are highest priority:

```text
reading current chapter
search
detail
explicit user-triggered content fetch
```

V8 work is background priority. It may be paused, cancelled, or delayed while
foreground work is active. When foreground requests drain, V8 scheduling should
resume aggressively enough to finish catalog validation without requiring the
user to keep interacting with the page.

The ordinary full-shelf maintenance pass is the scheduling surface for V8. It
orders source-engine books by mark-cache state:

```text
stale catalog-tail cache
missing mark cache
current catalog-tail cache
```

Current-cache books may run in small concurrent batches because the expected
path is a persisted mark-cache hit. Stale and missing-cache books stay
sequential, and the V8 detector itself remains globally single-concurrency.
Reader pull-to-refresh also triggers a background V8 run for the current
source-engine book; foreground reading/search/detail requests still preempt it.

## Validation Plan

1. Run source-engine V8 tests.
2. Run app V8 cache/tracker/router/provider tests.
3. Build the debug APK.
4. Install on the connected Android device.
5. Use AI Bridge to open shelf books and trigger V8.
6. Pull and parse V8 cache files.
7. For each book with a wrong mark, manually inspect the first wrong chapter and
   one or two neighboring chapters.
8. For books whose first wrong mark appears before the tail, inspect the rest of
   the tail for unvalidated gaps.

## Target Shelf Books

```text
青山
清光宝鉴
叩问仙道
仙都
元始法则
苟在武道世界成圣
仙人消失之后
灵源仙途：我养的灵兽太懂感恩了
苟在两界修仙
我在修仙界万古长青
```

## Done Criteria

```text
no old detector package remains in source or test code
no production class/event/cache path names the obsolete detector line
V8 tests and app build pass
device V8 cache is schema-current
known polluted examples are caught
adjacent normal chapters are not marked WRONG
foreground reading is not blocked by V8 background validation
```

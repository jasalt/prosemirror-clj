# prosemirror-clj

A focused JVM Clojure port of ProseMirror's document model and step application,
for server-side collaborative-editing authorities. **Planning / experimental;
not yet suitable for validating client edits.** hyper-prosemirror will consume
this library only after the conformance gates pass.

## Scope

Parse ProseMirror JSON with a supplied schema, apply serialized steps, and run
`check()` before committing a batch. No DOM parser/serializer, editor state/view,
commands, transform builder, or structure helpers. JavaScript remains the client
implementation and the test-only differential reference.

## Porting plan and gates

Commit failing translated tests before implementation. Each phase must pass its
exit gate before starting the next. Preserve upstream algorithms and test cases;
use `clojure.test/are` tables where cases share a shape.

1. **Test builder and content expressions.** Port test-builder flattening and
   `<tag>` offsets; content-expression tokenizer/parser, NFA/DFA, matching and
   filler generation. Implement the minimal schema/node/fragment substrate this
   requires. Gate: every `test-content.ts` match/fill/fill3 case, nested builder
   tags, marks, and UTF-16 positions pass.
2. **Model values and validation.** Port Node, Fragment, Mark, compareDeep,
   schema attributes, JSON round trips, and recursive `check()`. Gate: translated
   `test-node.ts` and `test-mark.ts` cases pass, except ResolvedPos-dependent cases
   explicitly assigned to phase 3; add malformed JSON, numeric equality,
   schema identity and supplementary-Unicode regressions. No hidden skips.
3. **Positions and replacement.** Port resolvedpos and replace/Slice; translate
   resolve, slice, replace tests and the deferred mark-boundary tests. Omit or
   redesign the resolve cache rather than copying WeakMap to WeakHashMap.
4. **Steps.** Port map, step, replace-step, mark-step, attr-step; register every
   pinned upstream step JSON ID. Include StepResult and required helpers, not
   Transform or structure. Gate: apply/invert/map/merge tests for supported
   steps and explicit invalid-input tests. Authority-level unknown-attribute
   rejection is separate: upstream attr/docAttr can silently ignore unknown attrs.
5. **Differential and integration gate.** Run JSON fixtures through the pinned JS
   bundle and Clojure: valid/invalid steps, random sequences, UTF-16, marks,
   open slices, 10/2000-paragraph documents. Compare result JSON and acceptance,
   benchmark cached one-step edits and batches. Require PORTED.edn to match the
   consuming client's package.json exact model/transform pins before release.
   Only then wire into hyper-prosemirror's per-document authority.

## Version tracking

`PORTED.edn` records targets, not a claim of completed coverage. Upstream sources
are from https://code.haverbeke.berlin/ (GitHub mirrors may lag). Keep source file
boundaries: `pm.model.content`, `pm.model.node`, `pm.transform.replace-step`, etc.
Track exclusions and the last passing gates here when implementation lands.

## Differences from Upstream (design constraints)

- Kebab-case Clojure functions in one namespace per upstream file, rather than
  JS instance methods. JSON keys retain their exact camelCase spellings.
- Persistent document values; schema declaration order must be explicit because
  it controls default filler types and mark rank (do not rely on hash-map order).
- Java strings preserve JS UTF-16 position and substring semantics.
- Attribute deep comparison must equate integral/floating numeric values as JS
  does; ordinary Clojure `=` is insufficient. JSON `null` maps to `nil`.
- JS type validator `number` accepts JVM integer and floating-point numbers.
- JVM exceptions replace JS RangeError/SyntaxError; semantic rejection is the
  compatibility contract, not stack traces.
- No DOM functionality. Resolved-position caching is deferred; a JVM weak-key
  map with values pointing back to documents would leak.
- Test-builder tags are test metadata, never serialized document attributes.
- Step positions in JSON must be integral numbers (`3` or `3.0`). JS accepts
  `1.5` and computes nonsense; the port rejects it with the same
  "Invalid input for ...Step.fromJSON" message. The differential harness checks
  only that the port rejects such cases.
- Steps are plain maps keyed by `:step` (the JSON stepType) and dispatched with
  multimethods in the single namespace `pm.transform`, instead of one namespace
  per upstream step file. `pm.transform/apply-steps` is an addition: an
  all-or-nothing batch that never throws and reports the failing index.
- Mappings and slices are immutable values; `Mapping.appendMap` and friends return
  a new mapping. `StepMap.touches`/`forEach` are not ported (nothing uses them).
- `Node.slice`, `Node.replace` and `Node.resolve` live in `pm.model.replace` and
  `pm.model.resolvedpos` to avoid namespace cycles.

## Current checkpoint

All five phases have run. `clojure -M:test`: 82 tests, 705 assertions pass.

- **Phases 1-2** (content expressions, builder, Node/Fragment/Mark/JSON/check):
  all 62 `test-content.ts` match/fill/fill3 assertions, all 41 `test-node.ts`
  cases and all 41 `test-mark.ts` cases (30 plus the 11 `ResolvedPos.marks` cases,
  now in `resolve_test.clj`).
- **Phase 3** (ResolvedPos, Slice, replace): `test-resolve.ts`, `test-slice.ts`
  and `test-replace.ts` translated.
- **Phase 4** (StepMap/Mapping, all nine step types): `test-mapping.ts`,
  `test-step.ts` merge cases and `test-replace_step.ts` translated, plus my own
  apply/invert/JSON/malformed-input regressions.
- **Not ported:** `test-trans.ts`, `test-invert.ts`, `test-structure.ts` and
  `test-droppoint.ts` all need the Transform builder or structure helpers, which
  are out of scope. Their step-level behavior is exercised only through the
  differential harness below.
- Several tests (`can-replace*`, supplied-content `create-and-fill`, the step
  JSON/malformed-input tables) are mine, written from the upstream algorithm.
  They are not translated cases.

### Differential harness (phase 5)

`diff/gen.mjs` runs the real prosemirror-model 1.25.13 / prosemirror-transform
1.12.7 under Node over a seeded random workload and records, per step: accept or
reject, failure message, resulting document JSON, step map ranges, `toJSON`,
inverse, `check()` result, plus position mapping, `step.map` and `step.merge`.
`test/pm/diff_test.clj` replays the same input through the port and compares.
Run it with `cd diff && npm ci && node gen.mjs SEED COUNT`, then `clojure -M:diff`.

Workload: valid sequences produced with the real `Transform` helpers (insert,
delete, split, join, lift, wrap, setBlockType, mark steps, node marks, attrs,
docAttr, replaceRange), plus mutations of those steps (shifted or non-integer
positions, unknown step types, bad open depths, bad marks, unknown nodes,
forced `structure`). Result at the last run: seeds 1-4 with 1500 base cases each,
about 3,400 cases per seed, roughly 4,400 applied steps and 500 soft failures
per seed, **0 mismatches** on all of them. The first runs found three real bugs in
the port (reversed `Fragment.cut` ranges threw instead of returning empty, the
`check()` message lacked upstream's fragment suffix, and slice serialization
tested `> 0` instead of nonzero), each now pinned by a regression test. The other
early differences were not port bugs: JSON drops an `undefined` attr value, the
non-integral-position divergence below, and cases where both sides throw on
inverse (JS records the throw; the harness now compares that too).

What this does and does not show:

- It is agreement on one schema (basic + list) and a generated workload. It is
  strong evidence for the step/replace/mark paths, not a proof. Custom schemas,
  non-default `inclusive`/`excludes`, and `isolating`/`defining` interactions are
  covered only by the translated unit tests.
- Mutated cases where JS itself throws a `TypeError` (for example a slice that is
  deeper than the document) are compared as "both reject", not by message.
- Non-integral positions are an intentional divergence; see Differences.

### Benchmark (JVM, Temurin 27, no GraalVM needed)

On the spike fixtures the port's result JSON equals the JS result for both the
10- and 2000-paragraph documents. Cold: 50 steps on the 2000-paragraph document
took about 190 ms including JIT warm-up. Warm: about 1.7 ms for one step on the
2000-paragraph document (GraalJS measured 0.30 ms in the spike), and about 2 ms to
`check()` it. Fragment sizes are recomputed on each call rather than cached; that
is the obvious optimization if the authority needs it, but it has not been
profiled. Timings are from a single machine and a single run.

Do not treat this as a release. Remaining gates: the `PORTED.edn` pin test against
the consuming client's `package.json`, and the authority-level checks (unknown
attribute rejection for `attr`/`docAttr`, size limits) that live in
hyper-prosemirror, not in this library.

Implementation adaptation: content matches use an immutable DFA graph and a
state index instead of cyclic JS objects; fragments are vectors. Schema types
carry a dereferenceable `SchemaRef` whose printer hides the registry cycle, and
cache their content match and allowed mark set lazily.

## Development

Use a JDK and Clojure CLI (`devenv shell` in the sibling hyper-prosemirror repo
currently supplies both). Run `clojure -M:test` or `clojure -M:repl` (localhost
nREPL port 7892).
The implementation follows ProseMirror's MIT-licensed sources; retain upstream
copyright notices with translated code/tests.

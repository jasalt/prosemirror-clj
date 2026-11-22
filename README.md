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

## Current checkpoint

The content-expression parser and ordered NFA/DFA implementation pass all 62
translated upstream match/fill/fill3 assertions. Three builder regression tests
add 7 assertions for nested UTF-16 tags, nested marks, text joining, and attribute
overrides. Run all 69 assertions with `clojure -M:test`.

This is **not yet a completed phase 1**: the builder has only the basic named
helpers, not the general schema-driven `builders` API, link marks, or upstream's
bare leaf-builder shorthand. Schema and node values are a minimal substrate;
attribute validators, full mark types and JSON are not implemented. Phase 2 has
not started. Additional syntax-error/schema regression tests are also needed.
`PORTED.edn` intentionally still lists no completed phases.

Implementation adaptation: content matches use an immutable DFA graph and a
state index instead of cyclic JS objects; fragments currently use vectors.
Schema types carry a registry atom initialized during schema construction.

## Development

Use a JDK and Clojure CLI (`devenv shell` in the sibling hyper-prosemirror repo
currently supplies both). Run `clojure -M:test` or `clojure -M:repl` (localhost
nREPL port 7892).
The implementation follows ProseMirror's MIT-licensed sources; retain upstream
copyright notices with translated code/tests.

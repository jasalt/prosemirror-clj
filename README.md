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

Phase 1's content and builder gate passes: all 62 upstream match/fill/fill3
assertions, syntax-error regressions, nested UTF-16 tags, nested marks, text
joining, attribute overrides, general schema-driven builders, link marks and
bare leaf-builder shorthand.

Phase 2's gate passes. Inventory against upstream 1.25.13: all 41 `test-node.ts`
cases are translated, plus the 30 non-ResolvedPos `test-mark.ts` cases (the other
11 are the ResolvedPos.marks cases deferred to phase 3). Regressions added for
malformed JSON, numeric equality, schema identity and supplementary Unicode.
Implemented: Node JSON data/string conversion,
recursive check, attribute validators, numeric deep comparison, Mark set/JSON
operations, vector-backed Fragment normalization/cutting/editing, node traversal,
node-at, text extraction and debug strings (including custom leaf rendering).
Upstream node tree-operation tables and non-ResolvedPos mark tables are translated;
additional tests cover Unicode cuts, traversal pruning, schema identity,
constructor rejection and safe printing of schema cycles.
Also implemented and tested: `can-replace`, `can-replace-with`, `can-append`,
`create-and-fill` with supplied content, and Fragment JSON entry points.
Run `clojure -M:test`: 35 tests, 240 assertions currently pass.

Known limits: the upstream suite has no direct tests for several of these
additions (`can-replace*`, supplied-content `create-and-fill`); their tests here
are my own, derived from the upstream algorithm, not translated cases. Phase 3
will exercise them through replace tests. ResolvedPos-dependent methods remain
phase 3. Differential conformance and performance testing have not run. Do not
use this checkpoint as a production validation authority.

Implementation adaptation: content matches use an immutable DFA graph and a
state index instead of cyclic JS objects; fragments currently use vectors.
Schema types carry an identity-bearing, dereferenceable SchemaRef initialized
during schema construction; its printer hides the registry cycle. Fragment size
is currently computed rather than cached (benchmark before integration).

## Development

Use a JDK and Clojure CLI (`devenv shell` in the sibling hyper-prosemirror repo
currently supplies both). Run `clojure -M:test` or `clojure -M:repl` (localhost
nREPL port 7892).
The implementation follows ProseMirror's MIT-licensed sources; retain upstream
copyright notices with translated code/tests.

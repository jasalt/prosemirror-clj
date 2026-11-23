# prosemirror-clj

A JVM Clojure port of ProseMirror's document model, serialized steps, and position
mapping. Use it to read and validate ProseMirror documents, apply client-generated
edits on a server, and serialize the result without running JavaScript.

**Status: experimental, differentially tested; not a release.** The model and
step implementation are usable for evaluation, but this is not a complete editor
or a ready-made collaborative-editing authority. See [Current checkpoint](#current-checkpoint)
for coverage and remaining integration gates.

## Contents

- [Installation](#installation)
- [Quick start](#quick-start)
- [Data and JSON conventions](#data-and-json-conventions)
- [Schemas and construction](#schemas-and-construction)
- [Documents, fragments, and marks](#documents-fragments-and-marks)
- [Positions and slices](#positions-and-slices)
- [Steps and batches](#steps-and-batches)
- [Position mapping](#position-mapping)
- [Server-side validation](#server-side-validation)
- [Test builders](#test-builders)
- [Compatibility and scope](#compatibility-and-scope)
- [Development and testing](#development-and-testing)
- [Current checkpoint](#current-checkpoint)
- [License](#license)

## Installation

Use a JDK and the Clojure CLI. The project declares Clojure **1.12.6** and
`org.clojure/data.json` **2.5.2** in `deps.edn`. Node.js is needed only to generate
differential-test fixtures, not to use the library.

For a local checkout, add a dependency to your application's `deps.edn` (adjust
the path):

```clojure
{:deps {prosemirror-clj/prosemirror-clj {:local/root "../prosemirror-clj"}}}
```

This is a local dependency name, not a published Maven/Clojars coordinate. From
this repository, `clojure -M` starts a REPL with the library on the classpath.

## Quick start

Read a document, insert text with a serialized ProseMirror step, validate the
result, and write it back to JSON:

```clojure
(require '[pm.schema.basic :as basic]
         '[pm.model.node :as node]
         '[pm.transform :as transform])

(def schema basic/schema)

(def doc
  (node/from-json schema
    {:type "doc"
     :content [{:type "paragraph"
                :content [{:type "text" :text "Hello"}]}]}))

(node/check doc)                         ; => nil (valid)

(def step
  (transform/step-from-json schema
    {:stepType "replace"
     :from 6 :to 6
     :slice {:content [{:type "text" :text "!"}]}}))

(def result (transform/apply-steps [step] doc))

(when (:failed result)
  (throw (ex-info (:failed result) {:index (:index result)})))

(def edited (:doc result))
(node/check edited)                      ; => nil
(node/text-content edited)               ; => "Hello!"
(node/write-json edited)
; => "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"Hello!\"}]}]}"
```

Position `6` is after `Hello`: the paragraph's opening boundary occupies one
position, and its five UTF-16 code units occupy the next five. Both `doc` and
`edited` remain available; no document is mutated.

## Data and JSON conventions

Functions replace JavaScript instance methods: `(node/to-json doc)`, not
`doc.toJSON()`. Most function names are kebab-case, while schema-spec and JSON
keys keep their upstream spelling.

| Value | Clojure representation |
| --- | --- |
| Schema | Map with `:nodes`, `:marks`, and `:spec`; type registries are keyed by strings |
| Node | Map with `:type` (a type map), `:attrs`, `:content`, `:marks`, and `:text` for text nodes |
| Fragment | Persistent vector of nodes |
| Mark | Map with `:type` (a mark type map) and `:attrs` |
| Slice | `{:content fragment :open-start n :open-end n}` |
| Step | Map with `:step` naming the step type, plus step-specific fields |
| Step map | `{:ranges [start old-size new-size ...] :inverted? boolean}` |
| Mapping | Immutable map containing a sequence of step maps and optional mirror information |

These internal maps are **not** the wire format. Use the serialization functions
rather than encoding a node or step directly: internal types refer back to their
schema. Reuse the same schema instance for related nodes, marks, and steps;
types from independently constructed schemas have distinct identities.

JSON-facing functions consume/produce Clojure maps with **keyword keys**, string
type names, and vectors for JSON arrays. For example:

```clojure
(require '[clojure.data.json :as json])

;; Documents have string convenience functions:
(node/read-json schema "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\"}]}")
(node/write-json doc)

;; Steps use data maps; parse/encode strings separately:
(transform/step-from-json schema
  (json/read-str "{\"stepType\":\"replace\",\"from\":1,\"to\":2}"
                 :key-fn keyword))
(json/write-str (transform/step-to-json step))
```

JSON `null` is `nil`. A missing key remains missing: in particular, an absent
attribute-step `:value` means “use the attribute default”, whereas `:value nil`
is explicit null. Semantic equality functions (`node/eq`, `mark/eq`,
`fragment/eq`) compare numeric attribute values with JavaScript-like equality,
so `1` and `1.0` compare equal. Prefer them over Clojure `=` for model values.

## Schemas and construction

### Included schema

`pm.schema.basic/schema` is a ready-built schema; `pm.schema.basic/spec` is its
editable specification. It corresponds to the upstream basic schema with
`addListNodes(basic.spec.nodes, "paragraph block*", "block")`, without DOM keys.

- Nodes: `doc`, `paragraph`, `blockquote`, `horizontal_rule`, `heading`,
  `code_block`, `text`, `image`, `hard_break`, `ordered_list`, `bullet_list`,
  `list_item`.
- Marks, in rank order: `link`, `em`, `strong`, `code`.
- `heading` has `level` (default `1`); `ordered_list` has `order` (default `1`).
- `image` requires `src`; `alt` and `title` default to null. `link` requires
  `href`, has a nullable `title`, and is non-inclusive.
- Documents require one or more blocks. List items require a paragraph followed
  by zero or more blocks. Code blocks contain text and disallow marks.

Use a custom schema when your client's schema differs; this default is not a
universal schema for all ProseMirror applications.

### Custom schemas

Pass **ordered vectors of `[name spec]` pairs** for `:nodes` and `:marks`.
Declaration order determines default filler nodes and mark rank; do not rely on
hash-map iteration order.

```clojure
(require '[pm.model.schema :as s])

(def custom-schema
  (s/schema
    {:nodes [["doc" {:content "block+"
                     :attrs {:revision {:default 0 :validate "number"}}}]
             ["paragraph" {:content "inline*" :group "block"}]
             ["text" {:group "inline"}]]
     :marks [["strong" {}]
             ["link" {:attrs {:href {:validate "string"}}
                      :inclusive false}]]}))

(def paragraph-type (get-in custom-schema [:nodes "paragraph"]))
(def doc-type (get-in custom-schema [:nodes "doc"]))
(def strong (s/mark custom-schema "strong"))
(def paragraph
  (s/create-checked paragraph-type nil
    [(s/text custom-schema "Hello" [strong])]))
(def custom-doc (s/create-checked doc-type nil [paragraph]))
(node/check custom-doc)                  ; => nil
```

A schema must have a `"text"` type and a top node (`"doc"` by default; override
with `:topNode`). Content expressions support node/group names, sequences,
alternatives (`|`), parentheses, `?`, `*`, `+`, and repetition ranges such as
`{2,4}`. Node content cannot mix inline and block types.

Common specification keys:

- Nodes: `:content`, `:group`, `:inline`, `:atom`, `:attrs`, and `:marks`.
  `:marks ""` disallows marks, `:marks "_"` allows all, and a space-separated
  string selects mark names/groups. When omitted, inline-content nodes allow
  marks and other nodes do not.
- Marks: `:group`, `:inclusive`, `:excludes`, and `:attrs`. By default a mark
  excludes its own type; `:excludes ""` disables exclusion, and `"_"` excludes
  all marks. Names/groups can also be listed.
- Attributes: `{:default value :validate validator}`. Without `:default`, the
  attribute is required. Validators can be type strings such as `"string"`,
  `"number"`, or `"string|null"`, or a one-argument function that **throws** on
  invalid values (its return value is ignored).

Other upstream metadata can be retained in specs, but it does not provide
editor/Transform behavior here. For example, `:defining` does not implement
high-level replacement fitting.

### Construction and validation levels

| Function | Behavior |
| --- | --- |
| `(s/text schema text [marks])` | Creates nonempty text; empty strings are rejected |
| `(s/mark schema name [attrs])` | Creates a mark with computed attributes |
| `(s/create type)` / `(s/create type attrs children [marks])` | Computes attributes and normalizes children/mark order; does not validate content |
| `(s/create-checked type)` / `(s/create-checked type attrs children [marks])` | Also checks that this node's content matches its type |
| `(s/create-and-fill type)` / `(s/create-and-fill type attrs children [marks])` | Adds required content before/after supplied children; returns `nil` when it cannot fit |
| `(node/check node)` | Recursively checks content, attributes, and mark collections; returns `nil` or throws |

Here and below, square brackets in signatures indicate optional arguments, not
literal vectors. Child content accepts `nil`, one node, or a vector of nodes;
convert sequences with `vec`/`mapv`. Text nodes must be constructed with
`s/text`, not `s/create`.

`node/from-json` and `node/read-json` decode nodes but do **not** recursively
validate their content expressions. Call `node/check` after decoding untrusted
documents and after applying edits. `create-checked` is a local content check,
not a substitute for recursive validation.

## Documents, fragments, and marks

The following are the main model operations. All return new values rather than
mutating inputs.

### `pm.model.node`

- JSON: `from-json`, `to-json`, `read-json`, `write-json`.
- Validation/equality: `check`, `eq`, `same-markup`, `is-leaf?`, `node-size`.
- Content: `(copy node content)`, `(cut node from [to])`, `(node-at node pos)`.
- Text: `(text-content node)`, `(text-between node from to [separator [leaf-text]])`.
  `leaf-text` can be a string or a function of a leaf node. `to-string` produces
  a debug representation, not JSON.
- Traversal: `(nodes-between node from to f [start])`, `(descendants node f)`.
  The callback receives `(child position parent index)`; returning exactly
  `false` prevents descent into that child. The root itself is not visited.
- Content feasibility: `(content-match-at node index)`,
  `(can-replace node from to [replacement])`,
  `(can-replace-with node from to type [marks])`, `(can-append node other)`.
  **These `from`/`to` values are child indexes**, not document positions.

### `pm.model.fragment`

`empty` is `[]`. `from` and `from-array` normalize content, joining adjacent text
nodes with identical markup. `append` also joins compatible boundary text.

- Access: `child` (throws for a missing child), `maybe-child` (returns `nil`),
  `size` (position size), `find-index` (returns `{:index i :offset n}`).
- Editing: `cut` uses positions; `cut-by-index` uses child indexes.
  `replace-child`, `add-to-start`, and `add-to-end` return vectors.
- Comparison/serialization: `eq`, `to-json`, `from-json`, `to-string`.

`count` counts children; `fragment/size` counts their total position size. They
are not interchangeable. An empty fragment serializes as `nil`.

### `pm.model.mark`

`none` is `[]`. Use `(add-to-set mark marks)` to insert a mark respecting rank
and exclusions, `(remove-from-set mark marks)` to remove it, and
`(is-in-set mark marks)` to test membership. `set-from` sorts marks by rank but
does not enforce exclusions. `eq`, `same-set`, `to-json`, and `from-json` provide
comparison and serialization.

### `pm.model.content`

For low-level content-expression work, `(s/content-match type)` returns a match
state. `match-type` and `match-fragment` advance it (or return `nil`),
`valid-end?` tests whether content can end there, `default-type` selects a default
node type, and `fill-before` computes filler content. Match states use an
immutable DFA graph plus a state index, not cyclic JavaScript objects.

## Positions and slices

Positions use ProseMirror's token accounting:

- Text size is its **UTF-16 code-unit count**, not Unicode code points or grapheme
  clusters. A supplementary character such as `😀` takes two positions.
- A non-text leaf occupies one position.
- A non-leaf node occupies its content size plus two boundary positions.
- Document positions range from `0` to `(fragment/size (:content doc))`; the
  root's own boundaries are not included. Replacement ranges are half-open.

For `doc(paragraph("Hello"))`, the paragraph is at position `0`, its text starts
at `1`, text ends at `6`, and the document content ends at `7`.

```clojure
(require '[pm.model.fragment :as fragment]
         '[pm.model.resolvedpos :as rp]
         '[pm.model.replace :as replacement])

(fragment/size (:content doc))           ; => 7
(def resolved (rp/resolve doc 3))
(:depth resolved)                        ; => 1
(:parent-offset resolved)                ; => 2
(get-in (rp/parent resolved) [:type :name]) ; => "paragraph"
(rp/start resolved)                      ; => 1
(rp/end resolved)                        ; => 6
```

`pm.model.resolvedpos` also provides `node`, `doc`, `index`, `index-after`,
`before`, `after`, `node-before`, `node-after`, `pos-at-index`, `marks`,
`marks-across`, `shared-depth`, `same-parent`, and `block-range`. Depth-taking
functions default to the current depth; negative depths are relative to it.
A block range has accessors `range-start`, `range-end`, `range-parent`,
`range-start-index`, and `range-end-index`. There is no resolved-position cache.

Slices carry content plus the number of open ancestor levels on either side.
A closed slice has both depths `0`; an open slice can represent a selection
starting or ending inside nested nodes.

```clojure
(def hello (replacement/slice doc 1 6))
(replacement/slice-size hello)           ; => 5

(def insertion
  (replacement/make-slice [(s/text schema "!")] 0 0))
(node/text-content (replacement/replace doc 6 6 insertion)) ; => "Hello!"
```

`pm.model.replace` provides:

- `(slice doc from [to [include-parents]])` and `(replace doc from to slice)`.
  Replacement requires compatible content/open depths; it does not perform
  high-level Transform fitting.
- `empty-slice`, `(make-slice fragment open-start open-end)`,
  `(max-open fragment [open-isolating])` (defaults to `true`).
- `slice-size`, `slice-eq`, `slice-to-json`, `slice-from-json`, `slice-to-string`.
- `(insert-at slice pos fragment)` (returns `nil` if content does not fit) and
  `(remove-between slice from to)`.

An empty slice serializes as `nil`. Direct replacement throws on failure;
replacement steps wrap structural replacement errors as step-result failures.

## Steps and batches

All step operations live in `pm.transform`. Parse wire data with
`(step-from-json schema json)` and serialize with `(step-to-json step)`.
Unknown step types and malformed input throw exceptions.

### Supported steps

| JSON `stepType` | Constructor in `pm.transform` |
| --- | --- |
| `replace` | `(replace-step from to slice [structure?])` |
| `replaceAround` | `(replace-around-step from to gap-from gap-to slice insert [structure?])` |
| `addMark` | `(add-mark-step from to mark)` |
| `removeMark` | `(remove-mark-step from to mark)` |
| `markSet` | `(mark-set-step steps)` |
| `addNodeMark` | `(add-node-mark-step pos mark)` |
| `removeNodeMark` | `(remove-node-mark-step pos mark)` |
| `attr` | `(attr-step pos attr [value])` |
| `docAttr` | `(doc-attr-step attr [value])` |

`markSet` groups `addMark`/`removeMark` steps; its JSON parser rejects other
nested step types. Attribute names are strings. Omitting an attribute value
resets it to its default; explicitly supplying `nil` sets null (subject to the
attribute validator). Node attribute/mark steps use the position **before** the
node, not the start of its content. `structure?` guards structural replacements
against overwriting content; pass a boolean to constructors.

### Applying and handling errors

The argument order is **step(s), then document**:

```clojure
(transform/apply-step step doc)
; => {:doc updated-document :failed nil}
; or {:doc nil :failed "reason"}

(transform/apply-steps [step] doc)
; => {:doc updated-document :failed nil}
; or {:doc nil :failed "reason" :index 0}
```

`apply-step` returns a failure for upstream-style soft failures, but can **throw**
for hard errors such as invalid positions or attribute values. `apply-steps`
catches exceptions from applying each step and returns the zero-based failing
index. It stops at the first failure and exposes no partially updated document;
the original document is unchanged.

Batch application consumes **already parsed steps**. Parsing failures occur
outside it, and neither application function calls `node/check`. Eagerly parse
with `mapv` inside your own error boundary rather than passing a lazy sequence
that may throw while being realized. “All-or-nothing” describes the returned
document, not a database transaction or rollback of external effects.

### Inverting, mapping, and merging

- `(invert-step step doc-before-step)` computes an inverse using the document
  **before that step**. For a multi-step batch, retain each intermediate input
  document and apply the inverses in reverse order.
- `(get-map step)` returns the position map for a successful step. Mark and
  attribute changes have empty position maps.
- `(map-step step mapping)` remaps a step, or returns `nil` if it is eliminated.
- `(merge-steps first-step second-step)` returns a combined step when supported,
  otherwise `nil`.

```clojure
(def inverse (transform/invert-step step doc))
(node/eq doc (:doc (transform/apply-step inverse edited))) ; => true
```

## Position mapping

`pm.transform.map` implements immutable StepMap and Mapping values. Every
append operation returns a **new mapping**; ignoring its return value discards
the change.

```clojure
(require '[pm.transform.map :as mapping])

(def position-map (transform/get-map step))
(:ranges position-map)                   ; => [6 0 1]
(mapping/map-pos position-map 6 -1)       ; => 6 (before inserted text)
(mapping/map-pos position-map 6 1)        ; => 7 (after inserted text)

(def maps (mapping/append-map (mapping/mapping) position-map))
(mapping/map-pos maps 7)                 ; => 8
(mapping/map-pos (mapping/invert maps) 8) ; => 7
```

- `(step-map ranges [inverted?])` takes triples of start, old size, and new size.
  `empty-step-map`, `offset-map`, and `invert-map` construct common maps.
- `(mapping)` or `(mapping step-maps)` constructs a mapping.
- `append-map`, `append-mapping`, `append-mapping-inverted`, `invert`, and
  `slice` build or select mapping pipelines. Mirror relationships are supported
  by `append-map`'s optional mirror index, `set-mirror`, and `get-mirror`.
- `(map-pos map-or-mapping pos [assoc])` returns a position. Association defaults
  to `1`; use `-1` to associate with the preceding side at an insertion boundary.
- `(map-result map-or-mapping pos [assoc])` additionally reports deletion
  information. Inspect it with `deleted?`, `deleted-before?`, `deleted-after?`,
  and `deleted-across?` rather than interpreting the internal bit field.

Mappings are building blocks, not a full collaborative rebasing protocol.

## Server-side validation

A server accepting edits should:

1. Use the same schema and compatible pinned ProseMirror versions as the client.
2. Enforce input byte size, document depth/size, step-count limits, authorization,
   and document-version checks at the application boundary.
3. Decode and `node/check` the starting document (or use an already validated,
   cached document).
4. Inspect raw input for application policy, then eagerly parse steps with
   `step-from-json`, catching parsing errors separately from application failures.
5. Apply steps in order, reject a failed batch, and recursively `node/check` the
   resulting document before committing it.
6. Commit the new document, version, and accepted step history atomically in
   the application's persistence layer.

**Unknown attributes require explicit policy checks.** Like upstream, attribute
construction retains declared attributes and can silently discard unknown ones.
In particular, an `attr`/`docAttr` step targeting an undeclared attribute can be
accepted as a no-op. A later `node/check` cannot detect information already
removed during construction. Reject unknown attribute names in the input if
that is required by your authority; do not treat successful parsing or a valid
resulting document as proof that every requested edit was meaningful.

The library does not implement authentication, storage, version negotiation,
concurrent commit control, resource limits, or a collaboration transport.

## Test builders

`pm.test-builder` provides a fixture DSL resembling `prosemirror-test-builder`:

```clojure
(require '[pm.test-builder :as b])

(def fixture (b/doc (b/p "He<a>llo" (b/strong "!"))))
(get-in (meta fixture) [:tag "a"])        ; => 3
(node/text-content fixture)              ; => "Hello!"
```

Tags such as `<a>` are removed from text and recorded as UTF-16 positions in
metadata; they are not document attributes and are never serialized. Built-in
builders include `doc`, `p`, `blockquote`, `pre`, `h1`, `hr`, `br`, `img`, `li`,
`ul`, `ol`, `em`, `strong`, `code`, and `a`.

The builder's default schema is a test fixture schema, not
`pm.schema.basic/schema`. To build values for your own schema, use
`(b/builders schema aliases)`; it returns a map keyed by builder-name strings:

```clojure
(def builders
  (b/builders schema {"p" {:nodeType "paragraph"}
                      "h2" {:nodeType "heading" :level 2}}))
(def fixture ((get builders "doc") ((get builders "h2") "Title")))
```

Builders accept an optional leading attributes map and do not replace recursive
validation of the resulting fixture.

## Compatibility and scope

[PORTED.edn](PORTED.edn) records the upstream targets:

| Package | Version | Revision, where recorded |
| --- | --- | --- |
| `prosemirror-model` | 1.25.13 | `819b986` |
| `prosemirror-transform` | 1.12.7 | `fe4882e` |
| `prosemirror-test-builder` | 1.1.1 | `a76003e` |
| `prosemirror-schema-basic` | 1.2.5 | — |
| `prosemirror-schema-list` | 1.5.1 | — |

The implementation follows upstream algorithms but is not a complete API port.
Included: content-expression matching/filling, model values and JSON, recursive
validation, resolved positions, slices/replacement, all nine pinned step types,
and position mapping.

Not included:

- DOM parsing/serialization, editor state/view, selections, commands, plugins,
  history, or collaboration protocol.
- The high-level `Transform` builder and structure/drop-point helpers (for
  example automatic split, join, lift, wrap, and replacement fitting). Steps
  generated by those JavaScript helpers can still be applied here.
- Resolved-position caching; StepMap `touches` and `forEach`.

Important adaptations and differences:

- Persistent maps/vectors replace mutable class instances. Schema types have an
  identity-bearing reference to their schema and lazy content/mark-set caches.
- JVM strings preserve JavaScript UTF-16 offset and substring semantics.
- JSON step positions and slice open depths must be integral numbers. `3.0` is
  accepted and normalized; fractional values are rejected even where upstream
  accepts them and later misbehaves. Low-level constructors are not wire-input
  validators.
- JSON `structure` uses JavaScript truthiness, not Clojure truthiness. Slice
  `openStart`/`openEnd` follow upstream's `value || 0` defaulting.
- Attribute validators accept JVM integer and floating-point values for
  `"number"`; semantic deep comparison equates numerically equal values.
- Exceptions replace JavaScript RangeError/SyntaxError. Rejection semantics,
  not JVM exception classes or stack traces, are the compatibility target.
- `Node.slice`/`Node.replace` live in `pm.model.replace`, and `Node.resolve` in
  `pm.model.resolvedpos`, to avoid namespace cycles. Step operations are grouped
  in `pm.transform`; `apply-steps` is a port-specific convenience.

## Development and testing

Run commands from the repository root unless noted:

```sh
clojure -M:test                         # default unit and pin tests
clojure -M:test pm.transform.step-test  # one test namespace
clojure -M:repl                         # nREPL, 127.0.0.1:7892
```

### Differential tests

Generate reference cases with Node.js and the exactly pinned JavaScript packages,
then replay them in Clojure:

```sh
(cd diff && npm ci && node gen.mjs 1 1500)
clojure -M:diff
```

The generator arguments are `SEED COUNT`. It writes `diff/cases.json`; each run
replaces the previous fixture, so replay it before generating another seed.
The replay itself only needs the JVM and an existing fixture. It is not part of
`clojure -M:test`.

The workload uses the real JavaScript Transform helpers to generate valid edit
sequences, plus malformed mutations. Comparisons cover acceptance, resulting
JSON, failure messages where applicable, step serialization, inverse steps,
step maps, position mapping, step mapping/merging, and recursive validation.
Operations include text edits, open slices, split/join, lift/wrap, block/node
changes, marks, and attributes. Fractional positions are checked for intentional
rejection; cases where JavaScript throws a TypeError are compared as rejection
rather than requiring identical messages.

The default pin test checks `PORTED.edn` against `diff/package.json`. It does
**not** check a consuming application's client dependencies.

### Source layout

| Path | Responsibility |
| --- | --- |
| `src/pm/model/` | Content expressions, schema, node, fragment, mark, positions, replacement, deep comparison |
| `src/pm/schema/basic.clj` | Basic + list schema specification and instance |
| `src/pm/transform.clj` | Step constructors, dispatch, JSON, application, inversion, mapping, merging |
| `src/pm/transform/map.clj` | StepMap, Mapping, deletion/recovery information |
| `src/pm/test_builder.clj` | Tagged test fixtures |
| `test/pm/` | Translated upstream tests and port-specific regressions |
| `diff/` | Pinned JavaScript reference generator and generated cases |

## Current checkpoint

The default suite was verified at **87 tests, 746 assertions, zero failures or
errors**. All five implementation phases recorded in `PORTED.edn` have run;
that is not a claim of complete upstream coverage or production readiness.

Translated tests cover content expressions, nodes, marks, resolved positions,
slices, replacement, mappings, step merges, and replacement steps. Additional
port-specific tests cover API behavior, malformed input, numeric equality,
schema identity, UTF-16, and step application/inversion/serialization.
`test-trans`, `test-invert`, `test-structure`, and `test-droppoint` have not been
ported: they depend on the out-of-scope Transform builder/structure helpers.
Some corresponding step-level behavior is exercised by differential testing.

The previously recorded differential run used seeds 1–4 with 1,500 base cases
per seed (roughly 3,400–3,460 generated cases and 5,800–5,900 applied steps per
seed), with zero mismatches. This is evidence for the tested paths, not a proof:
the generator uses one basic + list schema, and custom-schema interactions have
only unit-test coverage. Run the commands above to verify a fresh workload.

Remaining release/integration gates include exact pin checks against the
consuming client and authority-level unknown-attribute rejection. Application
resource limits and transactional integration also remain the consumer's
responsibility. No production performance guarantee is made; fragment sizes
are currently recomputed rather than cached.

## License

MIT; see [LICENSE](LICENSE). This port follows ProseMirror's MIT-licensed sources
and tests by Marijn Haverbeke and contributors. Retain upstream copyright and
license notices with translated code and tests.

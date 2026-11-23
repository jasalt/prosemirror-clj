// Differential reference generator. Runs the real ProseMirror JS over a seeded
// random workload and writes cases.json for the Clojure side to replay.
//   node gen.mjs [seed] [count]   (defaults: seed 1, count 400)
// Everything here is deterministic for a given seed + pinned package versions.
import {Schema, Slice, Fragment} from "prosemirror-model"
import {Transform, Step, Mapping, StepMap} from "prosemirror-transform"
import {schema as basic} from "prosemirror-schema-basic"
import {addListNodes} from "prosemirror-schema-list"
import {writeFileSync, readFileSync} from "node:fs"

export const schema = new Schema({
  nodes: addListNodes(basic.spec.nodes, "paragraph block*", "block"),
  marks: basic.spec.marks
})

const seed = +(process.argv[2] || 1), count = +(process.argv[3] || 400)
let state = seed >>> 0
function rnd() { // mulberry32
  state = (state + 0x6D2B79F5) >>> 0
  let t = state
  t = Math.imul(t ^ (t >>> 15), t | 1)
  t ^= t + Math.imul(t ^ (t >>> 7), t | 61)
  return ((t ^ (t >>> 14)) >>> 0) / 4294967296
}
const r = n => Math.floor(rnd() * n)
const pick = a => a[r(a.length)]

const words = ["a", "bc", "def", "ghij", "k l", "😀", "é", "x y z"]
const text = () => pick(words) + (r(3) ? "" : pick(words))
const marksFor = () => {
  const m = []
  if (!r(4)) m.push(schema.mark("em"))
  if (!r(5)) m.push(schema.mark("strong"))
  if (!r(8)) m.push(schema.mark("link", {href: "/" + r(3)}))
  return m
}
const inline = () => {
  const out = []
  for (let n = r(4); n > 0; n--) {
    const k = r(8)
    if (k == 0) out.push(schema.nodes.image.create({src: "i" + r(3)}, null, r(3) ? [] : [schema.mark("em")]))
    else if (k == 1) out.push(schema.nodes.hard_break.create())
    else out.push(schema.text(text(), marksFor()))
  }
  return out
}
function block(depth) {
  const k = r(depth > 1 ? 4 : 8)
  if (k < 2) return schema.nodes.paragraph.create(null, inline())
  if (k == 2) return schema.nodes.heading.create({level: 1 + r(3)}, inline())
  if (k == 3) return schema.nodes.code_block.create(null, r(2) ? [schema.text(text())] : [])
  if (k == 4) return schema.nodes.horizontal_rule.create()
  if (k == 5) return schema.nodes.blockquote.create(null, Array.from({length: 1 + r(2)}, () => block(depth + 1)))
  const item = () => schema.nodes.list_item.create(null, [schema.nodes.paragraph.create(null, inline()), ...(r(3) ? [] : [block(depth + 1)])])
  return (k == 6 ? schema.nodes.bullet_list : schema.nodes.ordered_list)
    .create(k == 6 ? null : {order: 1 + r(3)}, Array.from({length: 1 + r(2)}, item))
}
const randomDoc = () => schema.nodes.doc.create(null, Array.from({length: 1 + r(4)}, () => block(0)))

// ---- valid steps, produced with the real Transform helpers ----
function randomOp(tr) {
  const size = tr.doc.content.size
  const pos = () => r(size + 1)
  const mark = () => pick([schema.mark("em"), schema.mark("strong"), schema.mark("link", {href: "/" + r(3)}), schema.mark("code")])
  const a = pos(), b = Math.min(size, a + r(12))
  switch (r(16)) {
    case 0: case 1: tr.addMark(a, b, mark()); break
    case 2: tr.removeMark(a, b, mark()); break
    case 3: case 4: tr.insertText(text(), pos()); break
    case 5: tr.delete(a, b); break
    case 6: tr.replaceWith(a, b, schema.text(text())); break
    case 7: tr.replace(a, b, new Slice(Fragment.from([schema.nodes.paragraph.create(), schema.nodes.paragraph.create()]), 1, 1)); break
    case 8: tr.split(a); break
    case 9: tr.join(a); break
    case 10: { const $a = tr.doc.resolve(a), $b = tr.doc.resolve(b), rg = $a.blockRange($b); if (rg) tr.lift(rg, Math.max(0, rg.depth - 1)); break }
    case 11: { const rg = tr.doc.resolve(a).blockRange(tr.doc.resolve(b)); if (rg) tr.wrap(rg, [{type: pick([schema.nodes.blockquote, schema.nodes.bullet_list])}]); break }
    case 12: tr.setBlockType(a, b, pick([schema.nodes.paragraph, schema.nodes.heading, schema.nodes.code_block]), {level: 1 + r(3)}); break
    case 13: tr.setNodeMarkup(a, null, null, null); break
    case 14: {
      const n = tr.doc.nodeAt(a)
      if (!n) break
      const k = r(4)
      if (k == 0) tr.addNodeMark(a, pick([schema.mark("em"), schema.mark("strong"), schema.mark("link", {href: "/" + r(2)})]))
      else if (k == 1) tr.removeNodeMark(a, pick([schema.mark("em"), schema.marks.strong, schema.marks.link]))
      else if (k == 2) tr.setNodeAttribute(a, pick(["level", "order", "src", "alt"]), pick([2, 3, "s", null]))
      else tr.setNodeMarkup(a, null, n.attrs, [schema.mark("em")])
      break
    }
    case 15: if (r(3) == 0) { tr.step(new (Step.fromJSON(schema, {stepType: "docAttr", attr: "x", value: 1}).constructor)("x", r(3))); break }
      tr.replaceRange(a, b, new Slice(Fragment.from(schema.nodes.paragraph.create(null, [schema.text(text())])), 0, 0)); break
  }
}

function validCase(id) {
  const doc = randomDoc(), tr = new Transform(doc)
  for (let n = 1 + r(6); n > 0; n--) { try { randomOp(tr) } catch (e) { /* helper refused: skip */ } }
  return {id, doc: doc.toJSON(), steps: tr.steps.map(s => s.toJSON()), origin: "valid"}
}

// ---- mutations: perturb a valid case so that many steps become invalid ----
function mutate(c, id) {
  const steps = JSON.parse(JSON.stringify(c.steps))
  if (!steps.length) return null
  const s = pick(steps), kind = r(7)
  const numeric = ["from", "to", "pos", "gapFrom", "gapTo", "insert"].filter(k => k in s)
  if (kind == 0 && numeric.length) s[pick(numeric)] += pick([-3, -1, 1, 2, 5, 1000])
  else if (kind == 1 && numeric.length) s[pick(numeric)] = pick([-1, 99999, 1.5, "1", null])
  else if (kind == 2) s.stepType = pick(["evil", "replaceAround", "attr", "addMark", "markSet", ""])
  else if (kind == 3 && s.slice) s.slice.openStart = (s.slice.openStart || 0) + pick([-1, 1, 3])
  else if (kind == 4 && s.mark) s.mark = pick([{type: "nope"}, {type: "link"}, {type: "link", attrs: {href: 3}}, null])
  else if (kind == 5) s.structure = true
  else if (kind == 6 && s.slice) s.slice.content = [{type: pick(["script", "text", "doc", "paragraph", "image"]), ...(r(2) ? {text: "t"} : {})}]
  else return null
  return {id, doc: c.doc, steps, origin: "mutated"}
}

// ---- reference execution ----
const ranges = m => m.ranges
function runCase(c) {
  const out = {id: c.id, doc: c.doc, steps: c.steps, origin: c.origin, per: [], mapped: [], merges: []}
  let doc
  try { doc = schema.nodeFromJSON(c.doc); doc.check() } catch (e) { out.start = {threw: e.message}; return out }
  const parsed = []
  for (const sj of c.steps) {
    let step, res
    try { step = Step.fromJSON(schema, sj) }
    catch (e) { out.per.push({status: "threw", stage: "parse", reason: e.message}); break }
    try { res = step.apply(doc) } catch (e) { out.per.push({status: "threw", stage: "apply", reason: e.message}); break }
    if (res.failed) { out.per.push({status: "failed", reason: res.failed}); break }
    const rec = {status: "ok", doc: res.doc.toJSON(), map: ranges(step.getMap()), roundTrip: step.toJSON()}
    try { rec.inverse = step.invert(doc).toJSON() } catch (e) { rec.inverseThrew = e.message }
    try { res.doc.check(); rec.check = true } catch (e) { rec.check = e.message }
    out.per.push(rec)
    parsed.push({step, before: doc})
    doc = res.doc
  }
  // Position mapping through all applied steps.
  if (parsed.length) {
    const mapping = new Mapping(parsed.map(p => p.step.getMap()))
    const size = Math.min(40, parsed[0].before.content.size)
    for (let pos = 0; pos <= size; pos++) for (const assoc of [-1, 1]) {
      const m = mapping.mapResult(pos, assoc)
      out.mapped.push([pos, assoc, m.pos, m.deleted, m.deletedBefore, m.deletedAfter, m.deletedAcross])
    }
    // step.map(): the first step mapped over the maps of the rest.
    if (parsed.length > 1) {
      const rest = new Mapping(parsed.slice(1).map(p => p.step.getMap()))
      try { const m = parsed[0].step.map(rest); out.stepMap = m ? m.toJSON() : null } catch (e) { out.stepMapThrew = e.message }
    }
    // merge of consecutive steps.
    for (let i = 0; i + 1 < parsed.length; i++) {
      try { const m = parsed[i].step.merge(parsed[i + 1].step); out.merges.push(m ? m.toJSON() : null) }
      catch (e) { out.merges.push({threw: e.message}) }
    }
  }
  return out
}

const cases = []
for (let i = 0; i < count; i++) {
  const v = validCase("v" + i)
  cases.push(runCase(v))
  for (let k = 0; k < 2; k++) { const m = mutate(v, "m" + i + "." + k); if (m) cases.push(runCase(m)) }
}

const versions = n => JSON.parse(readFileSync(`node_modules/${n}/package.json`, "utf8")).version
const stats = {total: cases.length, okSteps: 0, failed: 0, threw: 0, withMerges: 0, stepTypes: {}}
for (const c of cases) {
  for (const p of c.per) { if (p.status == "ok") stats.okSteps++; else if (p.status == "failed") stats.failed++; else stats.threw++ }
  for (const s of c.steps) stats.stepTypes[s.stepType] = (stats.stepTypes[s.stepType] || 0) + 1
  stats.withMerges += c.merges.filter(Boolean).length
}
writeFileSync("cases.json", JSON.stringify({
  seed, count, versions: {model: versions("prosemirror-model"), transform: versions("prosemirror-transform")},
  stats, cases
}))
console.log(JSON.stringify(stats))

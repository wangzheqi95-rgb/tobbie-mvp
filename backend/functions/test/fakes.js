"use strict";
/** 記憶體版 Firestore／Storage（只實作 handlers 用到的部分），讓後端邏輯能在沒有網路的地方測試。 */
let autoId = 0;
class Doc {
  constructor(store, path) { this.store = store; this.path = path; this.id = path.split("/").pop(); }
  get ref() { return this; }
  async get() { const d = this.store.docs.get(this.path); return { exists: !!d, id: this.id, ref: this, data: () => d && JSON.parse(JSON.stringify(d)) }; }
  async set(obj, opt) { const cur = this.store.docs.get(this.path); this.store.docs.set(this.path, opt && opt.merge && cur ? { ...cur, ...obj } : { ...obj }); }
  async update(obj) { const cur = this.store.docs.get(this.path); if (!cur) throw new Error("NOT_FOUND " + this.path); this.store.docs.set(this.path, { ...cur, ...obj }); }
  async delete() { this.store.docs.delete(this.path); }
  collection(name) { return new Col(this.store, `${this.path}/${name}`); }
}
class Col {
  constructor(store, path, filters = []) { this.store = store; this.path = path; this.filters = filters; }
  doc(id) { return new Doc(this.store, `${this.path}/${id || "auto" + (++autoId)}`); }
  where(f, op, v) { return new Col(this.store, this.path, [...this.filters, [f, op, v]]); }
  async get() {
    const depth = this.path.split("/").length + 1;
    const docs = [...this.store.docs.keys()]
      .filter((k) => k.startsWith(this.path + "/") && k.split("/").length === depth)
      .map((k) => new Doc(this.store, k))
      .filter((d) => this.filters.every(([f, op, v]) => {
        const x = this.store.docs.get(d.path)[f];
        return op === "==" ? x === v : op === ">" ? x != null && x > v : op === "<" ? x != null && x < v : false;
      }));
    const snaps = await Promise.all(docs.map((d) => d.get()));
    return { empty: !snaps.length, docs: snaps };
  }
}
function fakeDeps(start = 1_700_000_000_000) {
  const store = { docs: new Map() };
  const files = new Map();
  const sent = { push: [], line: [] };
  let t = start;
  return {
    store, files, sent,
    advance(ms) { t += ms; },
    deps: {
      db: { collection: (n) => new Col(store, n) },
      bucket: { file: (p) => ({ save: async (b) => files.set(p, b), delete: async () => files.delete(p) }) },
      now: () => t,
      chat: async (name, history) => `${name}好～（收到 ${history.length} 則）`,
      sendPush: async (tokens, msg) => { sent.push.push({ tokens, msg }); return tokens.length; },
      sendLine: async (to, text) => { sent.line.push({ to, text }); },
    },
  };
}
module.exports = { fakeDeps };

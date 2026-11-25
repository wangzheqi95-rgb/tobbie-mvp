"use strict";
/**
 * 業務邏輯。所有外部服務（Firestore、Storage、推播、LINE、AI）都由 deps 注入，
 * 正式環境由 index.js 接上 Firebase，測試時接上記憶體假物件。
 *
 * deps = { db, bucket, now(), chat(name, history), sendPush(tokens, msg), sendLine(lineUserId, text) }
 */
const C = require("./core");

class HttpError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}

// ------------------------------------------------------------------ 機器人端 HTTPS API
async function authDevice(deps, header) {
  const a = C.parseDeviceAuth(header);
  if (!a) throw new HttpError(401, "缺少或格式錯誤的裝置驗證");
  const snap = await deps.db.collection("devices").doc(a.deviceId).get();
  if (!snap.exists || !C.safeEqual(snap.data().keyHash, C.sha256(a.deviceKey))) throw new HttpError(401, "裝置驗證失敗");
  return { id: a.deviceId, ref: snap.ref, data: snap.data() };
}

async function issuePairingCode(deps, deviceId) {
  for (let i = 0; i < 5; i++) {
    const code = C.newPairingCode();
    const ref = deps.db.collection("pairingCodes").doc(code);
    const s = await ref.get();
    if (s.exists && s.data().expiresAt > deps.now()) continue; // 撞號就重抽
    const expiresAt = deps.now() + C.PAIRING_TTL_MS;
    await ref.set({ deviceId, expiresAt });
    return { pairingCode: code, pairingExpiresAt: expiresAt };
  }
  throw new HttpError(503, "暫時無法產生配對碼，請稍後再試");
}

async function register(deps, body) {
  const deviceId = String(body && body.deviceId || "");
  if (!/^[A-Za-z0-9-]{8,64}$/.test(deviceId)) throw new HttpError(400, "deviceId 格式錯誤");
  const ref = deps.db.collection("devices").doc(deviceId);
  if ((await ref.get()).exists) throw new HttpError(409, "此裝置已註冊；如需重設請由管理員處理");
  const deviceKey = C.newDeviceKey();
  await ref.set({
    keyHash: C.sha256(deviceKey), model: String(body.model || "").slice(0, 80), appVersion: String(body.appVersion || "").slice(0, 20),
    members: [], elderName: "", profile: "", settings: {}, state: "NEW", createdAt: deps.now(), lastSeen: deps.now(),
  });
  return { deviceKey, ...(await issuePairingCode(deps, deviceId)) };
}

const configOf = (d) => ({ elderName: d.elderName || "", profile: d.profile || "", settings: d.settings || {} });

async function heartbeat(deps, dev, body) {
  await dev.ref.update({
    lastSeen: deps.now(), state: String(body.state || "").slice(0, 20),
    chassis: String(body.chassis || "").slice(0, 40), appVersion: String(body.appVersion || "").slice(0, 20),
  });
  // 一小時內被家屬或守護中心「遠端結案」的事件，讓機器人停止警報
  const recent = await dev.ref.collection("incidents").where("resolved", ">", deps.now() - 3600 * 1000).get();
  return { ok: true, config: configOf(dev.data), resolvedIncidents: recent.docs.map((d) => d.id) };
}

async function postIncident(deps, dev, body) {
  const v = C.validateIncident(body, dev.id);
  if (v.errs.length) throw new HttpError(400, v.errs.join("；"));
  const base = `incidents/${dev.id}/${v.summary.id}`;
  let snapshotPath = null;
  if (body.snapshotJpegBase64) {
    const buf = Buffer.from(String(body.snapshotJpegBase64), "base64");
    if (buf.length > 2 * 1024 * 1024) throw new HttpError(413, "照片太大");
    snapshotPath = `${base}.jpg`;
    await deps.bucket.file(snapshotPath).save(buf, { contentType: "image/jpeg" });
  }
  await deps.bucket.file(`${base}.json`).save(JSON.stringify(v.full), { contentType: "application/json" });
  const ref = dev.ref.collection("incidents").doc(v.summary.id);
  await ref.set({ ...v.summary, snapshotPath, detailPath: `${base}.json`, elderName: dev.data.elderName || "", createdAt: deps.now(), updatedAt: deps.now() }, { merge: true });
  return { ok: true, id: v.summary.id };
}

async function patchIncident(deps, dev, id, body) {
  const ref = dev.ref.collection("incidents").doc(id);
  const snap = await ref.get();
  if (!snap.exists) throw new HttpError(404, "找不到這個事件");
  const v = C.validateIncident({ ...body, id }, dev.id);
  if (v.errs.length) throw new HttpError(400, v.errs.join("；"));
  const prev = snap.data();
  // 守護中心已結案的話，不讓機器人覆蓋結案資訊
  const summary = { ...v.summary };
  if (prev.resolved) { summary.resolved = prev.resolved; summary.resolvedBy = prev.resolvedBy; }
  await deps.bucket.file(prev.detailPath).save(JSON.stringify(v.full), { contentType: "application/json" });
  await ref.set({ ...summary, updatedAt: deps.now() }, { merge: true });
  return { ok: true };
}

async function chat(deps, dev, body) {
  const history = Array.isArray(body.history) ? body.history.slice(-12) : [];
  const name = String(body.name || dev.data.elderName || "您").slice(0, 20);
  const reply = await deps.chat(name, history.map((h) => ({ role: h.role === "user" ? "user" : "model", text: String(h.text || "").slice(0, 500) })));
  return { reply: reply || "" };
}

/** HTTPS 路由（不用 express，減少相依套件）。 */
async function route(deps, req) {
  const method = String(req.headers["x-http-method-override"] || req.method).toUpperCase();
  const path = String(req.path || "").replace(/\/+$/, "");
  const body = req.body && typeof req.body === "object" ? req.body : {};
  if (method === "POST" && path === "/api/device/register") return register(deps, body);
  const dev = await authDevice(deps, req.headers.authorization);
  if (method === "POST" && path === "/api/device/pairing-code") return issuePairingCode(deps, dev.id);
  if (method === "POST" && path === "/api/device/heartbeat") return heartbeat(deps, dev, body);
  if (method === "GET" && path === "/api/device/config") return configOf(dev.data);
  if (method === "POST" && path === "/api/device/incidents") return postIncident(deps, dev, body);
  const m = /^\/api\/device\/incidents\/([A-Za-z0-9_-]{6,80})$/.exec(path);
  if (method === "PATCH" && m) return patchIncident(deps, dev, m[1], body);
  if (method === "POST" && path === "/api/device/chat") return chat(deps, dev, body);
  throw new HttpError(404, "沒有這個路徑");
}

// ------------------------------------------------------------------ 家屬／守護中心（callable，需登入）
async function userOf(deps, uid) {
  if (!uid) throw new HttpError(401, "請先登入");
  const s = await deps.db.collection("users").doc(uid).get();
  return { uid, role: "family", ...(s.exists ? s.data() : {}) };
}

async function audit(deps, uid, action, detail) {
  await deps.db.collection("auditLogs").doc().set({ uid, action, detail, at: deps.now() });
}

/** 家屬輸入機器人螢幕上的配對碼，把裝置綁到自己帳號。 */
async function bindDevice(deps, uid, data) {
  if (!uid) throw new HttpError(401, "請先登入");
  const code = String(data && data.code || "");
  if (!/^\d{6}$/.test(code)) throw new HttpError(400, "配對碼是 6 位數字");
  const cref = deps.db.collection("pairingCodes").doc(code);
  const c = await cref.get();
  if (!c.exists || c.data().expiresAt < deps.now()) throw new HttpError(404, "配對碼錯誤或已過期，請在機器人畫面上確認");
  const deviceId = c.data().deviceId;
  await cref.delete(); // 用過即失效
  const dref = deps.db.collection("devices").doc(deviceId);
  const d = (await dref.get()).data();
  const members = Array.from(new Set([...(d.members || []), uid]));
  await dref.update({ members });
  const uref = deps.db.collection("users").doc(uid);
  const u = await uref.get();
  const devices = Array.from(new Set([...((u.exists && u.data().devices) || []), deviceId]));
  await uref.set({ devices, role: (u.exists && u.data().role) || "family" }, { merge: true });
  await audit(deps, uid, "bindDevice", { deviceId });
  return { deviceId };
}

/** 家屬或守護中心結案。 */
async function resolveIncident(deps, uid, data) {
  const user = await userOf(deps, uid);
  const dref = deps.db.collection("devices").doc(String(data.deviceId || ""));
  const d = await dref.get();
  if (!d.exists || !C.canSeeDevice(user, d.data())) throw new HttpError(403, "沒有權限");
  const iref = dref.collection("incidents").doc(String(data.incidentId || ""));
  if (!(await iref.get()).exists) throw new HttpError(404, "找不到事件");
  const by = `${user.role === "guardian" ? "守護中心" : "家屬"} ${user.displayName || ""}`.trim();
  await iref.set({ resolved: deps.now(), resolvedBy: by, resolveNote: String(data.note || "").slice(0, 500), updatedAt: deps.now() }, { merge: true });
  await audit(deps, uid, "resolveIncident", { deviceId: dref.id, incidentId: iref.id });
  return { ok: true };
}

/** 家屬修改長者稱呼與資料（機器人下次心跳會收到）。 */
async function updateDeviceConfig(deps, uid, data) {
  const user = await userOf(deps, uid);
  const dref = deps.db.collection("devices").doc(String(data.deviceId || ""));
  const d = await dref.get();
  if (!d.exists || !C.canSeeDevice(user, d.data())) throw new HttpError(403, "沒有權限");
  const patch = {};
  if (typeof data.elderName === "string") patch.elderName = data.elderName.slice(0, 20);
  if (typeof data.profile === "string") patch.profile = data.profile.slice(0, 2000);
  await dref.update(patch);
  await audit(deps, uid, "updateDeviceConfig", { deviceId: dref.id, fields: Object.keys(patch) });
  return { ok: true };
}

/** 產生 LINE 綁定碼：家屬把這組碼傳給 Neara 的 LINE 官方帳號，就會收到警報。 */
async function createLineLinkCode(deps, uid) {
  if (!uid) throw new HttpError(401, "請先登入");
  const code = C.newLinkCode();
  await deps.db.collection("lineLinks").doc(code).set({ uid, expiresAt: deps.now() + C.PAIRING_TTL_MS });
  return { code };
}

/** LINE webhook：收到綁定碼 → 把 LINE userId 存到使用者資料。 */
async function lineWebhook(deps, events) {
  const replies = [];
  for (const ev of events || []) {
    if (ev.type !== "message" || !ev.message || ev.message.type !== "text") continue;
    const code = String(ev.message.text || "").trim().toUpperCase();
    if (!/^N\d{8}$/.test(code)) { replies.push({ replyToken: ev.replyToken, text: "請傳送家屬 App 上顯示的綁定碼（N 開頭 9 碼）。" }); continue; }
    const ref = deps.db.collection("lineLinks").doc(code);
    const s = await ref.get();
    if (!s.exists || s.data().expiresAt < deps.now()) { replies.push({ replyToken: ev.replyToken, text: "綁定碼錯誤或已過期，請在 App 重新產生。" }); continue; }
    await ref.delete();
    await deps.db.collection("users").doc(s.data().uid).set({ lineUserId: ev.source && ev.source.userId }, { merge: true });
    await audit(deps, s.data().uid, "linkLine", {});
    replies.push({ replyToken: ev.replyToken, text: "綁定成功！之後長輩有狀況，Neara 會在這裡通知您。" });
  }
  return replies;
}

// ------------------------------------------------------------------ 事件變動 → 通知
async function onIncidentWritten(deps, deviceId, before, after) {
  if (!after) return null;
  const dsnap = await deps.db.collection("devices").doc(deviceId).get();
  const device = dsnap.exists ? dsnap.data() : {};
  const msg = C.notificationFor(before, after, device.elderName);
  if (!msg) return null;
  const tokens = [], lines = [];
  for (const uid of device.members || []) {
    const u = await deps.db.collection("users").doc(uid).get();
    if (!u.exists) continue;
    tokens.push(...(u.data().fcmTokens || []));
    if (u.data().lineUserId) lines.push(u.data().lineUserId);
  }
  const data = { deviceId, incidentId: after.id, level: msg.level };
  const results = {
    push: tokens.length ? await deps.sendPush(tokens, { ...msg, data }) : 0,
    line: 0,
  };
  for (const l of lines) { await deps.sendLine(l, `${msg.title}\n${msg.body}`); results.line++; }
  await deps.db.collection("devices").doc(deviceId).collection("incidents").doc(after.id)
    .collection("notifications").doc().set({ ...msg, at: deps.now(), pushTokens: tokens.length, lineUsers: lines.length });
  return { msg, results };
}

// ------------------------------------------------------------------ 每日清除過期資料
async function purgeExpired(deps, incidentsQueryOlderThan) {
  const cutoff = C.retentionCutoff(deps.now());
  const docs = await incidentsQueryOlderThan(cutoff);
  let n = 0;
  for (const d of docs) {
    const x = d.data();
    for (const p of [x.snapshotPath, x.detailPath]) if (p) await deps.bucket.file(p).delete({ ignoreNotFound: true });
    await d.ref.delete();
    n++;
  }
  return n;
}

module.exports = {
  HttpError, route, authDevice, register, heartbeat, postIncident, patchIncident, chat,
  bindDevice, resolveIncident, updateDeviceConfig, createLineLinkCode, lineWebhook, onIncidentWritten, purgeExpired,
};

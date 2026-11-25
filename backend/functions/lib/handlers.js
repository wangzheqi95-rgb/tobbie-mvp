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
    members: [], guardianOrder: [], relations: {}, plan: "trial", integrations: {}, offlineNotified: false,
    elderName: "", profile: "", settings: {}, state: "NEW", createdAt: deps.now(), lastSeen: deps.now(),
  });
  return { deviceKey, ...(await issuePairingCode(deps, deviceId)) };
}

const configOf = (d) => ({ elderName: d.elderName || "", profile: d.profile || "", settings: d.settings || {} });

/** 機器人自動撥號清單：依監護人順序，只放有填電話、且沒關掉「緊急時自動來電」的人。 */
async function callListOf(deps, device) {
  const list = [];
  for (const uid of C.guardianOrder(device)) {
    const u = await deps.db.collection("users").doc(uid).get();
    if (!u.exists) continue;
    const phone = C.normalizePhone(u.data().phone);
    if (phone && C.prefsOf(u.data()).autoCall) list.push({ name: u.data().displayName || (device.relations || {})[uid] || "家人", phone });
  }
  return list;
}

async function heartbeat(deps, dev, body) {
  const patch = {
    lastSeen: deps.now(), offlineNotified: false, state: String(body.state || "").slice(0, 20),
    chassis: String(body.chassis || "").slice(0, 40), appVersion: String(body.appVersion || "").slice(0, 20),
  };
  // 長輩上次跟機器人說話的時間（家屬 App 首頁「上次聊天」）
  if (Number.isFinite(body.lastTalkAt) && body.lastTalkAt <= deps.now() + 60000) patch.lastTalkAt = body.lastTalkAt;
  // 機器人的推播 token（視訊來電響鈴用）
  if (typeof body.fcmToken === "string" && body.fcmToken.length < 4096) patch.fcmToken = body.fcmToken;
  // 智慧裝置連線狀態（目前：Health Connect 手錶）
  if (body.integrations && typeof body.integrations === "object") {
    const hc = body.integrations.healthConnect;
    if (typeof hc === "string") patch["integrations"] = { ...(dev.data.integrations || {}), healthConnect: { ...((dev.data.integrations || {}).healthConnect || {}), status: hc.slice(0, 30) } };
  }
  await dev.ref.update(patch);
  // 一小時內被「遠端結案」的事件 → 機器人停止警報；被家人按「我來處理」的 → 機器人告訴長輩誰要來
  const since = deps.now() - 3600 * 1000;
  const resolved = await dev.ref.collection("incidents").where("resolved", ">", since).get();
  const acked = await dev.ref.collection("incidents").where("ackAt", ">", since).get();
  return {
    ok: true,
    paired: (dev.data.members || []).length > 0,
    config: { ...configOf(dev.data), callList: await callListOf(deps, dev.data) },
    resolvedIncidents: resolved.docs.map((d) => d.id),
    ackedIncidents: acked.docs.map((d) => ({ id: d.id, by: d.data().ackByName || "家人" })),
  };
}

/** 機器人上傳 Health Connect 讀到的手錶資料（心率樣本、今天步數）。 */
async function postHealth(deps, dev, body) {
  const ref = dev.ref.collection("health").doc("latest");
  const cur = await ref.get();
  const old = cur.exists ? cur.data() : {};
  const hr = C.mergeHeartRate(old.hr, Array.isArray(body.hr) ? body.hr.slice(0, 500) : [], deps.now());
  const doc = { hr, updatedAt: deps.now() };
  if (Number.isFinite(body.stepsToday)) { doc.stepsToday = Math.max(0, Math.round(body.stepsToday)); doc.stepsDay = C.taipeiDayStart(deps.now()); }
  await ref.set(doc, { merge: true });
  const integrations = dev.data.integrations || {};
  await dev.ref.update({ integrations: { ...integrations, healthConnect: { ...(integrations.healthConnect || {}), status: "OK", lastSync: deps.now() } } });
  return { ok: true, kept: hr.length };
}

async function vitalsFor(deps, dev, start) {
  const h = await dev.ref.collection("health").doc("latest").get();
  return h.exists ? C.vitalsAround(h.data().hr, start) : null;
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
  const vitals = await vitalsFor(deps, dev, v.summary.start);
  await ref.set({ ...v.summary, vitals, snapshotPath, detailPath: `${base}.json`, elderName: dev.data.elderName || "", createdAt: deps.now(), updatedAt: deps.now() }, { merge: true });
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
  const vitals = await vitalsFor(deps, dev, summary.start); // 事件後的心率這時才收得到
  await ref.set({ ...summary, ...(vitals ? { vitals } : {}), updatedAt: deps.now() }, { merge: true });
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
  if (method === "POST" && path === "/api/device/health") return postHealth(deps, dev, body);
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
  const guardianOrder = Array.from(new Set([...C.guardianOrder(d), uid])); // 第一個綁的人＝主要監護人，之後的人排在後面
  const relation = C.RELATIONS.includes(data.relation) ? data.relation : "其他";
  const uref = deps.db.collection("users").doc(uid);
  const u = await uref.get();
  // 家人之間看不到彼此的使用者資料，所以把顯示名稱抄一份在裝置上（只給同一台的家人看）
  const memberNames = { ...(d.memberNames || {}), [uid]: (u.exists && u.data().displayName) || relation };
  await dref.update({ members, guardianOrder, relations: { ...(d.relations || {}), [uid]: relation }, memberNames });
  const devices = Array.from(new Set([...((u.exists && u.data().devices) || []), deviceId]));
  await uref.set({ devices, role: (u.exists && u.data().role) || "family" }, { merge: true });
  await audit(deps, uid, "bindDevice", { deviceId, invitedBy: c.data().invitedBy || null });
  return { deviceId, primary: guardianOrder[0] === uid };
}

async function deviceFor(deps, user, deviceId) {
  const dref = deps.db.collection("devices").doc(String(deviceId || ""));
  const d = await dref.get();
  if (!d.exists || !C.canSeeDevice(user, d.data())) throw new HttpError(403, "沒有權限");
  return { ref: dref, data: d.data() };
}

/** 已綁定的家人產生邀請碼，讓其他家人加入（用法跟機器人螢幕上的配對碼一樣）。 */
async function createInviteCode(deps, uid, data) {
  const user = await userOf(deps, uid);
  const dev = await deviceFor(deps, user, data.deviceId);
  for (let i = 0; i < 5; i++) {
    const code = C.newPairingCode();
    const ref = deps.db.collection("pairingCodes").doc(code);
    const s = await ref.get();
    if (s.exists && s.data().expiresAt > deps.now()) continue;
    const expiresAt = deps.now() + C.PAIRING_TTL_MS;
    await ref.set({ deviceId: dev.ref.id, expiresAt, invitedBy: uid });
    await audit(deps, uid, "createInviteCode", { deviceId: dev.ref.id });
    return { code, expiresAt };
  }
  throw new HttpError(503, "暫時無法產生邀請碼");
}

/**
 * 調整監護人：順序（只有主要監護人或守護中心能改）、稱謂、移除成員（主要監護人可移除別人；每個人都可以退出自己）。
 * data = { deviceId, order?: [uid…], relations?: {uid: 稱謂}, remove?: uid }
 */
async function updateGuardians(deps, uid, data) {
  const user = await userOf(deps, uid);
  const dev = await deviceFor(deps, user, data.deviceId);
  const d = dev.data;
  const current = C.guardianOrder(d);
  const isPrimary = current[0] === uid;
  const isStaff = user.role === "guardian" || user.role === "admin";
  let members = [...(d.members || [])];
  let order = current;
  const relations = { ...(d.relations || {}) };
  let memberNames = { ...(d.memberNames || {}) };

  if (data.remove) {
    const target = String(data.remove);
    if (!members.includes(target)) throw new HttpError(404, "這個人不在監護人名單裡");
    if (target !== uid && !isPrimary && !isStaff) throw new HttpError(403, "只有主要監護人可以移除其他家人");
    members = members.filter((m) => m !== target);
    order = order.filter((m) => m !== target);
    delete relations[target];
    memberNames = { ...memberNames }; delete memberNames[target];
    const uref = deps.db.collection("users").doc(target);
    const u = await uref.get();
    if (u.exists) await uref.set({ devices: (u.data().devices || []).filter((x) => x !== dev.ref.id) }, { merge: true });
  }
  if (Array.isArray(data.order)) {
    if (!isPrimary && !isStaff) throw new HttpError(403, "只有主要監護人可以調整通知順序");
    const o = data.order.map(String);
    if (o.length !== order.length || new Set(o).size !== o.length || !o.every((x) => order.includes(x))) throw new HttpError(400, "順序必須包含所有監護人，且不能重複");
    order = o;
  }
  if (data.relations && typeof data.relations === "object") {
    for (const [k, v] of Object.entries(data.relations)) {
      if (!members.includes(k)) continue;
      if (k !== uid && !isPrimary && !isStaff) throw new HttpError(403, "只能改自己的稱謂");
      if (C.RELATIONS.includes(v)) relations[k] = v;
    }
  }
  await dev.ref.update({ members, guardianOrder: order, relations, memberNames });
  await audit(deps, uid, "updateGuardians", { deviceId: dev.ref.id, remove: data.remove || null, reordered: Array.isArray(data.order) });
  return { ok: true, order };
}

/** 家屬改自己的電話、顯示名稱、通知偏好。緊急警報不能關。 */
async function updateMyProfile(deps, uid, data) {
  if (!uid) throw new HttpError(401, "請先登入");
  const patch = {};
  if (typeof data.displayName === "string") patch.displayName = data.displayName.trim().slice(0, 20);
  if (data.phone !== undefined) {
    if (data.phone === "" || data.phone === null) patch.phone = "";
    else { const p = C.normalizePhone(data.phone); if (!p) throw new HttpError(400, "電話格式不正確"); patch.phone = p; }
  }
  if (data.notifyPrefs && typeof data.notifyPrefs === "object") {
    const np = {};
    for (const k of Object.keys(C.DEFAULT_PREFS)) if (typeof data.notifyPrefs[k] === "boolean") np[k] = data.notifyPrefs[k];
    const u = await deps.db.collection("users").doc(uid).get();
    patch.notifyPrefs = { ...C.prefsOf(u.exists ? u.data() : {}), ...np };
  }
  await deps.db.collection("users").doc(uid).set(patch, { merge: true });
  if (patch.displayName) {
    const u = await deps.db.collection("users").doc(uid).get();
    for (const did of (u.data().devices || [])) {
      const dref = deps.db.collection("devices").doc(did);
      const ds = await dref.get();
      if (ds.exists && (ds.data().members || []).includes(uid)) await dref.update({ memberNames: { ...(ds.data().memberNames || {}), [uid]: patch.displayName } });
    }
  }
  return { ok: true };
}

/** 家人按「我來處理」：停止往下一位升級，並告訴其他收到通知的人與機器人。 */
async function ackIncident(deps, uid, data) {
  const user = await userOf(deps, uid);
  const dev = await deviceFor(deps, user, data.deviceId);
  const iref = dev.ref.collection("incidents").doc(String(data.incidentId || ""));
  const s = await iref.get();
  if (!s.exists) throw new HttpError(404, "找不到事件");
  const inc = s.data();
  if (inc.ackAt) return { ok: true, already: true, by: inc.ackByName };
  const staff = user.role === "guardian" || user.role === "admin";
  const name = staff ? `守護中心 ${user.displayName || ""}`.trim() : (user.displayName || (dev.data.relations || {})[uid] || "家人");
  await iref.set({ ackBy: uid, ackByName: name, ackAt: deps.now(), escNextAt: null, updatedAt: deps.now() }, { merge: true });
  const others = (inc.escNotified || []).filter((x) => x !== uid);
  const msg = { level: "info", title: `${name} 已接手處理`, body: `${dev.data.elderName || "長輩"}的警報由 ${name} 處理中。` };
  const sent = await notifyUsers(deps, others, msg, { deviceId: dev.ref.id, incidentId: iref.id });
  await audit(deps, uid, "ackIncident", { deviceId: dev.ref.id, incidentId: iref.id });
  return { ok: true, by: name, notified: sent.users };
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

// ------------------------------------------------------------------ 通知
/** 發給一群使用者（推播＋LINE），回傳各發了幾則。 */
async function notifyUsers(deps, uids, msg, data) {
  const tokens = [], lines = [];
  for (const uid of [...new Set(uids)]) {
    const u = await deps.db.collection("users").doc(uid).get();
    if (!u.exists) continue;
    tokens.push(...(u.data().fcmTokens || []));
    if (u.data().lineUserId) lines.push(u.data().lineUserId);
  }
  const push = tokens.length ? await deps.sendPush(tokens, { ...msg, data: { ...data, level: msg.level } }) : 0;
  for (const l of lines) await deps.sendLine(l, `${msg.title}\n${msg.body}`);
  return { users: [...new Set(uids)].length, push, line: lines.length, tokens: tokens.length };
}

/** 守護中心值班人員（role = guardian）。 */
async function staffUids(deps) {
  const s = await deps.db.collection("users").where("role", "==", "guardian").get();
  return s.docs.map((d) => d.id);
}

async function usersWithPref(deps, uids, key) {
  const out = [];
  for (const uid of uids) {
    const u = await deps.db.collection("users").doc(uid).get();
    if (u.exists && C.prefsOf(u.data())[key]) out.push(uid);
  }
  return out;
}

async function logNotification(deps, deviceId, incidentId, msg, extra) {
  await deps.db.collection("devices").doc(deviceId).collection("incidents").doc(incidentId)
    .collection("notifications").doc().set({ ...msg, ...extra, at: deps.now() });
}

/**
 * 事件新增或更新時：
 *  - 變成「警報」→ 守護中心＋第 1 位家人同時通知，並排好 3 分鐘後的升級；
 *  - 其他狀態（確認中、長輩說沒事、已結案）→ 依每個人的通知偏好發一般通知。
 */
async function onIncidentWritten(deps, deviceId, before, after) {
  if (!after) return null;
  const dsnap = await deps.db.collection("devices").doc(deviceId).get();
  const device = dsnap.exists ? dsnap.data() : {};
  const msg = C.notificationFor(before, after, device.elderName);
  if (!msg) return null;
  const data = { deviceId, incidentId: after.id };
  const order = C.guardianOrder(device);
  const iref = deps.db.collection("devices").doc(deviceId).collection("incidents").doc(after.id);

  if (msg.level === "critical") {
    const step = C.escalationStep(order, 0, deps.now());
    const family = step.who ? [step.who] : [];
    const center = C.centerEnabled(device);
    const results = await notifyUsers(deps, [...family, ...(center ? await staffUids(deps) : [])], msg, data);
    await iref.set({ escLevel: 0, escNotified: family, escNextAt: step.nextAt, centerNotifiedAt: center ? deps.now() : null }, { merge: true });
    await logNotification(deps, deviceId, after.id, msg, { to: family, center, results });
    return { msg, to: family, center, results };
  }
  const key = !before ? "checking" : after.outcome === "CANCELLED" && before.outcome !== "CANCELLED" ? "cancelled" : null;
  const to = key ? await usersWithPref(deps, order, key) : order; // 「已結案」一律通知
  const results = await notifyUsers(deps, to, msg, data);
  await logNotification(deps, deviceId, after.id, msg, { to, results });
  return { msg, to, results };
}

/** 每分鐘：找出到期還沒人接手的警報，通知下一位家人。 */
async function escalateDue(deps, dueIncidentDocs) {
  let n = 0;
  for (const s of dueIncidentDocs) {
    const inc = s.data();
    if (inc.ackAt || inc.resolved || inc.outcome !== "ALERTED" || !inc.escNextAt || inc.escNextAt > deps.now()) continue;
    const deviceId = inc.deviceId;
    const device = (await deps.db.collection("devices").doc(deviceId).get()).data() || {};
    const order = C.guardianOrder(device);
    const level = (inc.escLevel || 0) + 1;
    const step = C.escalationStep(order, level, deps.now());
    const notified = [...(inc.escNotified || [])];
    if (step.who) {
      const prevName = (device.relations || {})[order[level - 1]] || "前一位家人";
      const msg = { level: "critical", title: `緊急：${device.elderName || "長輩"}可能跌倒且沒有回應`, body: `${prevName} 3 分鐘內沒有接手，請您立即聯絡或前往查看。` };
      const results = await notifyUsers(deps, [step.who], msg, { deviceId, incidentId: inc.id });
      notified.push(step.who);
      await logNotification(deps, deviceId, inc.id, msg, { to: [step.who], escLevel: level, results });
      n++;
    }
    await s.ref.set({ escLevel: level, escNotified: notified, escNextAt: step.nextAt, updatedAt: deps.now() }, { merge: true });
  }
  return n;
}

/** 每 5 分鐘：超過 30 分鐘沒心跳的機器人，通知家人一次（恢復連線後重置）。 */
async function notifyOffline(deps, staleDeviceDocs) {
  let n = 0;
  for (const s of staleDeviceDocs) {
    const d = s.data();
    if (d.offlineNotified || !(d.members || []).length || d.lastSeen > deps.now() - C.OFFLINE_MS) continue;
    const to = await usersWithPref(deps, C.guardianOrder(d), "offline");
    const mins = Math.round((deps.now() - d.lastSeen) / 60000);
    await notifyUsers(deps, to, { level: "info", title: `${d.elderName || "長輩"}的機器人離線了`, body: `已經 ${mins} 分鐘沒有回報，請確認電源與網路。` }, { deviceId: s.id });
    await s.ref.update({ offlineNotified: true });
    n++;
  }
  return n;
}

/** 每晚 9 點：平安摘要（只發給有開這個選項的家人）。 */
async function dailySummary(deps, deviceDocs) {
  const dayStart = C.taipeiDayStart(deps.now());
  let n = 0;
  for (const s of deviceDocs) {
    const d = s.data();
    const to = await usersWithPref(deps, C.guardianOrder(d), "daily");
    if (!to.length) continue;
    const incs = (await s.ref.collection("incidents").where("start", ">", dayStart - 1).get()).docs.map((x) => x.data());
    const alerts = incs.filter((x) => x.outcome === "ALERTED").length;
    const h = await s.ref.collection("health").doc("latest").get();
    const steps = h.exists && h.data().stepsDay === dayStart ? h.data().stepsToday : null;
    const parts = [incs.length ? `今天有 ${incs.length} 次偵測（其中 ${alerts} 次警報）` : "今天沒有任何跌倒偵測"];
    if (steps != null) parts.push(`走了 ${steps.toLocaleString("en-US")} 步`);
    if (d.lastSeen < deps.now() - C.OFFLINE_MS) parts.push("機器人目前離線");
    await notifyUsers(deps, to, { level: "info", title: `${d.elderName || "長輩"}今天平安摘要`, body: parts.join("，") + "。" }, { deviceId: s.id });
    n++;
  }
  return n;
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
  postHealth, createInviteCode, updateGuardians, updateMyProfile, ackIncident, escalateDue, notifyOffline, dailySummary, notifyUsers,
};

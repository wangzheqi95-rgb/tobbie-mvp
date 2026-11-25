"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const crypto = require("crypto");
const H = require("../lib/handlers");
const C = require("../lib/core");
const { fakeDeps } = require("./fakes");

const req = (method, path, body, auth) => ({ method, path, body, headers: auth ? { authorization: auth } : {} });
const incident = (deviceId, over = {}) => ({
  schema: "neara.incident.v1", id: "NEARA-abc123-1700000000000", deviceId, reason: "LYING", reasonLabel: "偵測到疑似跌倒",
  start: 1_700_000_000_000, lastWell: 1_699_999_990_000, outcome: "PENDING", outcomeLabel: "處理中", chassis: "石頭 S6",
  dialog: [{ t: 1, who: "機器人", text: "您還好嗎？" }], pre: [{ t: 1, deg: 10, p: null }], post: [], ...over,
});

test("核心：裝置驗證字串解析與配對碼格式", () => {
  assert.deepEqual(C.parseDeviceAuth("Device dev-12345678:abcdefghijklmnop1234"), { deviceId: "dev-12345678", deviceKey: "abcdefghijklmnop1234" });
  assert.equal(C.parseDeviceAuth("Bearer x"), null);
  assert.match(C.newPairingCode(), /^\d{6}$/);
  assert.match(C.newLinkCode(), /^N\d{8}$/);
});

test("核心：通知規則", () => {
  const p = { outcome: "PENDING", reason: "LYING", reasonLabel: "偵測到疑似跌倒" };
  assert.equal(C.notificationFor(null, p, "王阿嬤").level, "info");
  assert.equal(C.notificationFor(p, { ...p, outcome: "ALERTED" }, "王阿嬤").level, "critical");
  assert.match(C.notificationFor(p, { ...p, outcome: "CANCELLED" }, "王阿嬤").title, /沒事/);
  assert.equal(C.notificationFor({ ...p, outcome: "ALERTED" }, { ...p, outcome: "ALERTED" }), null); // 沒變就不重複通知
});

test("核心：LINE 簽章驗證", () => {
  const body = Buffer.from('{"events":[]}');
  const sig = crypto.createHmac("sha256", "secret").update(body).digest("base64");
  assert.equal(C.verifyLineSignature(body, sig, "secret"), true);
  assert.equal(C.verifyLineSignature(body, sig, "wrong"), false);
});

test("完整流程：註冊 → 配對 → 心跳 → 上傳事件 → 警報通知 → 遠端結案 → 清除過期", async () => {
  const f = fakeDeps();
  const d = f.deps;
  const devId = "dev-0000-1111-2222";

  // 註冊
  const reg = await H.route(d, req("POST", "/api/device/register", { deviceId: devId, model: "Pixel", appVersion: "0.1.0" }));
  assert.ok(reg.deviceKey.length >= 16 && /^\d{6}$/.test(reg.pairingCode));
  assert.equal(f.store.docs.get(`devices/${devId}`).keyHash, C.sha256(reg.deviceKey), "只存金鑰雜湊");
  await assert.rejects(H.route(d, req("POST", "/api/device/register", { deviceId: devId })), /已註冊/);
  const auth = `Device ${devId}:${reg.deviceKey}`;
  await assert.rejects(H.route(d, req("POST", "/api/device/heartbeat", {}, `Device ${devId}:wrongwrongwrongwrong`)), /驗證失敗/);

  // 家屬綁定
  await assert.rejects(H.bindDevice(d, "uidA", { code: "000000" }), /配對碼錯誤/);
  const b = await H.bindDevice(d, "uidA", { code: reg.pairingCode });
  assert.equal(b.deviceId, devId);
  await assert.rejects(H.bindDevice(d, "uidB", { code: reg.pairingCode }), /配對碼錯誤/, "配對碼用過即失效");
  await f.store.docs.set("users/uidA", { ...f.store.docs.get("users/uidA"), fcmTokens: ["tokA"], lineUserId: "Uline" });
  await H.updateDeviceConfig(d, "uidA", { deviceId: devId, elderName: "王阿嬤", profile: "82 歲；降血壓藥" });
  await assert.rejects(H.updateDeviceConfig(d, "uidStranger", { deviceId: devId, elderName: "x" }), /沒有權限/);

  // 心跳拿到家屬設定
  const hb = await H.route(d, req("POST", "/api/device/heartbeat", { state: "MONITOR" }, auth));
  assert.equal(hb.config.elderName, "王阿嬤");
  assert.deepEqual(hb.resolvedIncidents, []);

  // 機器人上傳事件（含照片）
  const inc = incident(devId);
  const r1 = await H.route(d, req("POST", "/api/device/incidents", { ...inc, snapshotJpegBase64: Buffer.from("jpeg").toString("base64") }, auth));
  assert.equal(r1.id, inc.id);
  const path = `devices/${devId}/incidents/${inc.id}`;
  const s1 = f.store.docs.get(path);
  assert.equal(s1.pre, undefined, "骨架不放 Firestore（放 Storage）");
  assert.ok(f.files.has(`incidents/${devId}/${inc.id}.json`) && f.files.has(`incidents/${devId}/${inc.id}.jpg`));
  const n1 = await H.onIncidentWritten(d, devId, null, s1);
  assert.equal(n1.msg.level, "info");

  // 別台裝置不能改這台的事件
  await assert.rejects(H.route(d, req("POST", "/api/device/incidents", incident("dev-other-9999"), auth)), /不符/);

  // 沒回應 → ALERTED → 緊急通知（推播＋LINE）
  const patch = (body) => ({ ...req("POST", `/api/device/incidents/${inc.id}`, body, auth), headers: { authorization: auth, "x-http-method-override": "PATCH" } });
  await H.route(d, patch({ ...inc, outcome: "ALERTED", outcomeLabel: "已發出警報" }));
  const s2 = f.store.docs.get(path);
  assert.equal(s2.outcome, "ALERTED");
  const n2 = await H.onIncidentWritten(d, devId, s1, s2);
  assert.equal(n2.msg.level, "critical");
  assert.deepEqual(f.sent.push.at(-1).tokens, ["tokA"]);
  assert.equal(f.sent.line.at(-1).to, "Uline");

  // 守護中心遠端結案 → 機器人心跳收到
  await f.store.docs.set("users/guard1", { role: "guardian", displayName: "值班員小林" });
  f.advance(5000);
  await H.resolveIncident(d, "guard1", { deviceId: devId, incidentId: inc.id, note: "已電話聯絡家屬" });
  assert.match(f.store.docs.get(path).resolvedBy, /守護中心 值班員小林/);
  const hb2 = await H.route(d, req("POST", "/api/device/heartbeat", { state: "ALERT" }, auth));
  assert.deepEqual(hb2.resolvedIncidents, [inc.id]);
  // 機器人之後再更新，不會蓋掉結案資訊
  await H.route(d, patch({ ...inc, outcome: "ALERTED", post: [{ t: 2, deg: 80, p: null }] }));
  assert.match(f.store.docs.get(path).resolvedBy, /守護中心/);
  assert.ok([...f.store.docs.keys()].some((k) => k.startsWith("auditLogs/")), "有操作紀錄");

  // AI 聊天代理
  const c = await H.route(d, req("POST", "/api/device/chat", { name: "王阿嬤", history: [{ role: "user", text: "我去散步" }] }, auth));
  assert.match(c.reply, /王阿嬤/);

  // 91 天後清除
  f.advance(91 * 24 * 3600 * 1000);
  const n = await H.purgeExpired(d, async (cutoff) => (await d.db.collection("devices").doc(devId).collection("incidents").where("start", "<", cutoff).get()).docs);
  assert.equal(n, 1);
  assert.equal(f.store.docs.get(path), undefined);
  assert.equal(f.files.size, 0, "照片與骨架檔一起刪除");
});

test("LINE 綁定流程", async () => {
  const f = fakeDeps(); const d = f.deps;
  const { code } = await H.createLineLinkCode(d, "uidA");
  const r = await H.lineWebhook(d, [{ type: "message", replyToken: "rt", message: { type: "text", text: code.toLowerCase() }, source: { userId: "Uxyz" } }]);
  assert.match(r[0].text, /綁定成功/);
  assert.equal(f.store.docs.get("users/uidA").lineUserId, "Uxyz");
  const r2 = await H.lineWebhook(d, [{ type: "message", replyToken: "rt", message: { type: "text", text: code }, source: { userId: "Uxyz" } }]);
  assert.match(r2[0].text, /過期/);
});

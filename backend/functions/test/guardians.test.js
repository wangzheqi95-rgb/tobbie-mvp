"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const H = require("../lib/handlers");
const C = require("../lib/core");
const { fakeDeps } = require("./fakes");

const req = (method, path, body, auth) => ({ method, path, body, headers: auth ? { authorization: auth } : {} });
const MIN = 60 * 1000;

/** 建一台機器人＋三位家人（大明主要、小美、阿華）＋一位守護中心值班員。 */
async function setup() {
  const f = fakeDeps(); const d = f.deps;
  const devId = "dev-aaaa-bbbb-cccc";
  const reg = await H.route(d, req("POST", "/api/device/register", { deviceId: devId }));
  const auth = `Device ${devId}:${reg.deviceKey}`;
  const b1 = await H.bindDevice(d, "ming", { code: reg.pairingCode, relation: "兒子" });
  assert.equal(b1.primary, true, "第一個綁的人是主要監護人");
  const inv = await H.createInviteCode(d, "ming", { deviceId: devId });
  const b2 = await H.bindDevice(d, "mei", { code: inv.code, relation: "女兒" });
  assert.equal(b2.primary, false);
  const inv2 = await H.createInviteCode(d, "mei", { deviceId: devId });
  await H.bindDevice(d, "hua", { code: inv2.code, relation: "孫子女" });
  await assert.rejects(H.createInviteCode(d, "stranger", { deviceId: devId }), /沒有權限/);
  for (const [uid, name] of [["ming", "大明"], ["mei", "小美"], ["hua", "阿華"]]) {
    await d.db.collection("users").doc(uid).set({ fcmTokens: [`tok-${uid}`] }, { merge: true });
    await H.updateMyProfile(d, uid, { displayName: name });
  }
  await d.db.collection("users").doc("guard1").set({ role: "guardian", displayName: "林專員", fcmTokens: ["tok-guard"] });
  await H.updateDeviceConfig(d, "ming", { deviceId: devId, elderName: "王阿嬤" });
  assert.deepEqual(f.store.docs.get(`devices/${devId}`).memberNames, { ming: "大明", mei: "小美", hua: "阿華" }, "名稱同步到裝置");
  return { f, d, devId, auth };
}

const tokensOf = (sent) => sent.flatMap((p) => p.tokens).sort();

test("核心：升級步驟、心率合併、事件前後心率", () => {
  assert.deepEqual(C.escalationStep(["a", "b", "c"], 0, 0), { who: "a", nextAt: C.ESCALATE_MS });
  assert.deepEqual(C.escalationStep(["a", "b", "c"], 2, 0), { who: "c", nextAt: null });
  assert.deepEqual(C.escalationStep(["a"], 0, 0), { who: "a", nextAt: null });
  assert.deepEqual(C.guardianOrder({ members: ["x", "y", "z"], guardianOrder: ["z", "gone", "x"] }), ["z", "x", "y"]);
  const now = 10_000_000;
  const hr = C.mergeHeartRate([{ t: now - 3 * 3600e3, bpm: 70 }, { t: now - 60e3, bpm: 74 }], [{ t: now - 60e3, bpm: 74 }, { t: now, bpm: 300 }, { t: now - 30e3, bpm: 80 }], now);
  assert.deepEqual(hr.map((s) => s.bpm), [74, 80], "去掉 2 小時前、重複、不合理的值");
  const v = C.vitalsAround([{ t: 100, bpm: 74 }, { t: 200, bpm: 100 }, { t: 300, bpm: 112 }], 150);
  assert.deepEqual([v.hrBefore, v.hrAfter], [74, 112]);
  assert.equal(C.vitalsAround([], 1), null);
  assert.equal(C.normalizePhone("0912-345-678"), "0912345678");
  assert.equal(C.normalizePhone("abc"), null);
});

test("警報：守護中心＋第 1 位同時通知，3 分鐘沒人接手依序升級，按「我來處理」就停", async () => {
  const { f, d, devId } = await setup();
  const inc = { id: "NEARA-esc-1", deviceId: devId, outcome: "PENDING", reason: "LYING", start: d.now() };
  const r = await H.onIncidentWritten(d, devId, inc, { ...inc, outcome: "ALERTED" });
  assert.equal(r.msg.level, "critical");
  assert.deepEqual(tokensOf(f.sent.push.splice(0)), ["tok-guard", "tok-ming"], "中心＋第 1 位同時");
  const path = `devices/${devId}/incidents/${inc.id}`;
  await f.store.docs.set(path, { ...f.store.docs.get(path), ...inc, outcome: "ALERTED" });
  assert.equal(f.store.docs.get(path).escNextAt, d.now() + 3 * MIN);

  const due = async () => (await d.db.collection("devices").doc(devId).collection("incidents").get()).docs;
  f.advance(2 * MIN);
  assert.equal(await H.escalateDue(d, await due()), 0, "還沒到 3 分鐘");
  f.advance(1 * MIN);
  assert.equal(await H.escalateDue(d, await due()), 1);
  const second = f.sent.push.splice(0);
  assert.deepEqual(tokensOf(second), ["tok-mei"], "第 2 位");
  assert.match(second[0].msg.body, /兒子 3 分鐘內沒有接手/);

  // 小美按「我來處理」→ 通知已收到的大明，之後不再升級
  const ack = await H.ackIncident(d, "mei", { deviceId: devId, incidentId: inc.id });
  assert.equal(ack.by, "小美");
  assert.deepEqual(tokensOf(f.sent.push.splice(0)), ["tok-ming"]);
  f.advance(10 * MIN);
  assert.equal(await H.escalateDue(d, await due()), 0);
  assert.equal(f.sent.push.length, 0, "阿華不會收到");
  const again = await H.ackIncident(d, "ming", { deviceId: devId, incidentId: inc.id });
  assert.equal(again.already, true);
});

test("沒人接手會一路升級到最後一位，之後停止；結案的不升級", async () => {
  const { f, d, devId } = await setup();
  const path = `devices/${devId}/incidents/NEARA-esc-2`;
  const inc = { id: "NEARA-esc-2", deviceId: devId, outcome: "ALERTED", reason: "LYING", start: d.now() };
  await f.store.docs.set(path, inc);
  await H.onIncidentWritten(d, devId, { ...inc, outcome: "PENDING" }, inc);
  f.sent.push.length = 0;
  const due = async () => (await d.db.collection("devices").doc(devId).collection("incidents").get()).docs;
  const order = [];
  for (let i = 0; i < 4; i++) { f.advance(3 * MIN); await H.escalateDue(d, await due()); order.push(...tokensOf(f.sent.push.splice(0))); }
  assert.deepEqual(order, ["tok-mei", "tok-hua"]);
  assert.equal(f.store.docs.get(path).escNextAt, null);

  // 另一件：已結案就不升級
  const p3 = `devices/${devId}/incidents/NEARA-esc-3`;
  await f.store.docs.set(p3, { ...inc, id: "NEARA-esc-3", escLevel: 0, escNotified: ["ming"], escNextAt: d.now(), resolved: d.now() });
  assert.equal(await H.escalateDue(d, await due()), 0);
});

test("試用期間預設通知中心；basic 方案不通知中心", async () => {
  const { f, d, devId } = await setup();
  await d.db.collection("devices").doc(devId).update({ plan: "basic" });
  const inc = { id: "NEARA-esc-4", deviceId: devId, outcome: "PENDING", reason: "LYING", start: d.now() };
  const r = await H.onIncidentWritten(d, devId, inc, { ...inc, outcome: "ALERTED" });
  assert.equal(r.center, false);
  assert.deepEqual(tokensOf(f.sent.push), ["tok-ming"]);
});

test("一般通知依偏好；調整順序、移除、權限", async () => {
  const { f, d, devId, auth } = await setup();
  await H.updateMyProfile(d, "mei", { notifyPrefs: { checking: false, emergency: false } });
  assert.equal(f.store.docs.get("users/mei").notifyPrefs.checking, false);
  assert.equal(f.store.docs.get("users/mei").notifyPrefs.emergency, undefined, "不認得的偏好（例如想關緊急警報）不會寫入");
  const inc = { id: "NEARA-info-1", deviceId: devId, outcome: "PENDING", reason: "LYING", reasonLabel: "偵測到疑似跌倒", start: d.now() };
  await H.onIncidentWritten(d, devId, null, inc);
  assert.deepEqual(tokensOf(f.sent.push.splice(0)), ["tok-hua", "tok-ming"], "小美關了「確認中」通知");

  await assert.rejects(H.updateGuardians(d, "mei", { deviceId: devId, order: ["mei", "ming", "hua"] }), /主要監護人/);
  await assert.rejects(H.updateGuardians(d, "ming", { deviceId: devId, order: ["mei", "ming"] }), /所有監護人/);
  await H.updateGuardians(d, "ming", { deviceId: devId, order: ["mei", "ming", "hua"] });
  assert.deepEqual(f.store.docs.get(`devices/${devId}`).guardianOrder, ["mei", "ming", "hua"]);
  // 現在小美是主要監護人，可以移除阿華；大明不行
  await assert.rejects(H.updateGuardians(d, "ming", { deviceId: devId, remove: "hua" }), /主要監護人/);
  await H.updateGuardians(d, "mei", { deviceId: devId, remove: "hua" });
  assert.deepEqual(f.store.docs.get(`devices/${devId}`).members.sort(), ["mei", "ming"]);
  assert.equal(f.store.docs.get(`devices/${devId}`).memberNames.hua, undefined);
  // 每個人都可以退出自己
  await H.updateGuardians(d, "ming", { deviceId: devId, remove: "ming" });
  assert.deepEqual(f.store.docs.get(`devices/${devId}`).members, ["mei"]);
  assert.deepEqual(f.store.docs.get("users/ming").devices, []);
  await assert.rejects(H.updateMyProfile(d, "mei", { phone: "電話" }), /格式/);

  // 心跳帶回撥號清單（依順序、只含有電話且沒關自動來電的人）
  await H.updateMyProfile(d, "mei", { phone: "0912-345-678" });
  const hb = await H.route(d, req("POST", "/api/device/heartbeat", { state: "MONITOR", integrations: { healthConnect: "NO_PERMISSION" }, lastTalkAt: d.now() - 1000 }, auth));
  assert.equal(hb.paired, true);
  assert.equal(f.store.docs.get(`devices/${devId}`).lastTalkAt, d.now() - 1000);
  assert.deepEqual(hb.config.callList, [{ name: "小美", phone: "0912345678" }]);
  await H.updateMyProfile(d, "mei", { notifyPrefs: { autoCall: false } });
  const hb2 = await H.route(d, req("POST", "/api/device/heartbeat", {}, auth));
  assert.deepEqual(hb2.config.callList, []);
  assert.equal(f.store.docs.get(`devices/${devId}`).integrations.healthConnect.status, "NO_PERMISSION");
});

test("手錶資料：上傳心率 → 事件報告帶事件前後心率；機器人心跳拿到誰接手", async () => {
  const { f, d, devId, auth } = await setup();
  const t0 = d.now();
  await H.route(d, req("POST", "/api/device/health", { hr: [{ t: t0 - 60e3, bpm: 74 }], stepsToday: 2840 }, auth));
  const incident = {
    schema: "neara.incident.v1", id: "NEARA-hr-1", deviceId: devId, reason: "LYING", start: t0, outcome: "PENDING", dialog: [], pre: [], post: [],
  };
  await H.route(d, req("POST", "/api/device/incidents", incident, auth));
  const path = `devices/${devId}/incidents/NEARA-hr-1`;
  assert.deepEqual([f.store.docs.get(path).vitals.hrBefore, f.store.docs.get(path).vitals.hrAfter], [74, null]);
  f.advance(2 * MIN);
  await H.route(d, req("POST", "/api/device/health", { hr: [{ t: t0 + 60e3, bpm: 112 }] }, auth));
  await H.route(d, { ...req("POST", "/api/device/incidents/NEARA-hr-1", { ...incident, outcome: "ALERTED" }, auth), headers: { authorization: auth, "x-http-method-override": "PATCH" } });
  assert.equal(f.store.docs.get(path).vitals.hrAfter, 112);
  assert.equal(f.store.docs.get(`devices/${devId}/health/latest`).stepsToday, 2840);
  assert.equal(f.store.docs.get(`devices/${devId}`).integrations.healthConnect.status, "OK");

  await H.ackIncident(d, "ming", { deviceId: devId, incidentId: "NEARA-hr-1" });
  const hb = await H.route(d, req("POST", "/api/device/heartbeat", {}, auth));
  assert.deepEqual(hb.ackedIncidents, [{ id: "NEARA-hr-1", by: "大明" }]);
});

test("離線通知只發一次、恢復後重置；每日摘要只發給有開的人", async () => {
  const { f, d, devId, auth } = await setup();
  const devs = async () => (await d.db.collection("devices").get()).docs;
  f.advance(20 * MIN);
  assert.equal(await H.notifyOffline(d, await devs()), 0);
  f.advance(15 * MIN);
  assert.equal(await H.notifyOffline(d, await devs()), 1);
  assert.match(f.sent.push.at(-1).msg.title, /離線/);
  assert.equal(await H.notifyOffline(d, await devs()), 0, "不重複通知");
  await H.route(d, req("POST", "/api/device/heartbeat", {}, auth));
  assert.equal(f.store.docs.get(`devices/${devId}`).offlineNotified, false);

  f.sent.push.length = 0;
  assert.equal(await H.dailySummary(d, await devs()), 0, "預設沒開每日摘要");
  await H.updateMyProfile(d, "hua", { notifyPrefs: { daily: true } });
  await H.route(d, req("POST", "/api/device/health", { stepsToday: 3100 }, auth));
  assert.equal(await H.dailySummary(d, await devs()), 1);
  assert.deepEqual(f.sent.push[0].tokens, ["tok-hua"]);
  assert.match(f.sent.push[0].msg.body, /沒有任何跌倒偵測，走了 3,100 步/);
});

test("長輩資料表單 → 整理成文字；測試警報只發給自己、每分鐘一次", async () => {
  const { f, d, devId } = await setup();
  await H.updateDeviceConfig(d, "mei", { deviceId: devId, elder: { age: "82歲", living: "獨居", meds: "降血壓藥（早上一顆）", allergies: "無", history: "" } });
  const dev = f.store.docs.get(`devices/${devId}`);
  assert.equal(dev.profile, "82 歲，獨居\n慣用藥物：降血壓藥（早上一顆）\n過敏：無");
  assert.equal(dev.elder.age, "82");
  f.sent.push.length = 0;
  const r = await H.sendTestAlert(d, "mei", { deviceId: devId });
  assert.equal(r.push, 1);
  assert.deepEqual(f.sent.push[0].tokens, ["tok-mei"], "只發給自己");
  assert.equal(f.sent.push[0].msg.level, "critical");
  await assert.rejects(H.sendTestAlert(d, "mei", { deviceId: devId }), /一分鐘/);
  f.advance(61 * 1000);
  await H.sendTestAlert(d, "mei", { deviceId: devId });
  await assert.rejects(H.sendTestAlert(d, "stranger", { deviceId: devId }), /沒有權限/);
});

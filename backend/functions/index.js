"use strict";
/**
 * Neara 雲端後端（Firebase Cloud Functions v2，區域 asia-east1 台灣）。
 * 業務邏輯在 lib/handlers.js；這裡只負責接上 Firebase 服務。
 *
 * 需要的 secrets（firebase functions:secrets:set 名稱）：
 *   GEMINI_KEY           AI 聊天（金鑰只放在後端）
 *   LINE_CHANNEL_TOKEN   LINE 官方帳號 Messaging API 的 channel access token
 *   LINE_CHANNEL_SECRET  LINE webhook 簽章驗證
 */
const { onRequest, onCall, HttpsError } = require("firebase-functions/v2/https");
const { onDocumentWritten } = require("firebase-functions/v2/firestore");
const { onSchedule } = require("firebase-functions/v2/scheduler");
const { setGlobalOptions, logger } = require("firebase-functions/v2");
const { defineSecret } = require("firebase-functions/params");
const admin = require("firebase-admin");
const H = require("./lib/handlers");
const C = require("./lib/core");

admin.initializeApp();
setGlobalOptions({ region: "asia-east1", maxInstances: 20 });

const GEMINI_KEY = defineSecret("GEMINI_KEY");
const LINE_TOKEN = defineSecret("LINE_CHANNEL_TOKEN");
const LINE_SECRET = defineSecret("LINE_CHANNEL_SECRET");

function deps() {
  return {
    db: admin.firestore(),
    bucket: admin.storage().bucket(),
    now: () => Date.now(),
    chat: geminiChat,
    sendPush,
    sendLine,
  };
}

async function geminiChat(name, history) {
  const key = GEMINI_KEY.value();
  if (!key) return "";
  for (const model of ["gemini-flash-lite-latest", "gemini-flash-latest"]) {
    const r = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "x-goog-api-key": key },
      body: JSON.stringify({
        systemInstruction: { parts: [{ text: C.systemPrompt(name) }] },
        contents: history.map((h) => ({ role: h.role, parts: [{ text: h.text }] })),
        generationConfig: { temperature: 0.8, maxOutputTokens: 1024 },
      }),
    });
    if (r.status === 404 || r.status === 400) continue;
    if (!r.ok) { logger.warn("gemini error", r.status); return ""; }
    const j = await r.json();
    return ((j.candidates && j.candidates[0] && j.candidates[0].content && j.candidates[0].content.parts) || [])
      .map((p) => p.text || "").join("").replace(/[*#>`]/g, "").trim();
  }
  return "";
}

/** FCM 推播：critical 用高優先度與「緊急」通知頻道（家屬 App 會建立 neara_critical 頻道）。 */
async function sendPush(tokens, msg) {
  const critical = msg.level === "critical";
  const res = await admin.messaging().sendEachForMulticast({
    tokens,
    notification: { title: msg.title, body: msg.body },
    data: Object.fromEntries(Object.entries(msg.data || {}).map(([k, v]) => [k, String(v)])),
    android: { priority: "high", notification: { channelId: critical ? "neara_critical" : "neara_info", sound: "default" } },
    apns: { headers: { "apns-priority": "10" }, payload: { aps: { sound: critical ? { critical: 1, name: "default", volume: 1 } : "default" } } },
  });
  return res.successCount;
}

async function sendLine(to, text) {
  const token = LINE_TOKEN.value();
  if (!token) return;
  const r = await fetch("https://api.line.me/v2/bot/message/push", {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
    body: JSON.stringify({ to, messages: [{ type: "text", text: text.slice(0, 4900) }] }),
  });
  if (!r.ok) logger.warn("LINE push failed", r.status, await r.text());
}

const asHttps = (e) => {
  if (e instanceof H.HttpError) {
    const code = { 400: "invalid-argument", 401: "unauthenticated", 403: "permission-denied", 404: "not-found", 409: "already-exists" }[e.status] || "internal";
    return new HttpsError(code, e.message);
  }
  logger.error(e);
  return new HttpsError("internal", "伺服器錯誤");
};

// ---- 機器人端 HTTPS API
exports.api = onRequest({ secrets: [GEMINI_KEY], cors: false, timeoutSeconds: 60 }, async (req, res) => {
  try {
    res.json(await H.route(deps(), req));
  } catch (e) {
    const status = e instanceof H.HttpError ? e.status : 500;
    if (status === 500) logger.error(e);
    res.status(status).json({ ok: false, error: status === 500 ? "伺服器錯誤" : e.message });
  }
});

// ---- 家屬／守護中心（callable，需 Firebase 登入）
const callable = (fn) => onCall(async (req) => {
  try { return await fn(deps(), req.auth && req.auth.uid, req.data || {}); } catch (e) { throw asHttps(e); }
});
exports.bindDevice = callable(H.bindDevice);
exports.resolveIncident = callable(H.resolveIncident);
exports.updateDeviceConfig = callable(H.updateDeviceConfig);
exports.createLineLinkCode = callable((d, uid) => H.createLineLinkCode(d, uid));
exports.createInviteCode = callable(H.createInviteCode);   // 邀請其他家人
exports.updateGuardians = callable(H.updateGuardians);     // 通知順序、稱謂、移除
exports.updateMyProfile = callable(H.updateMyProfile);     // 電話、名稱、通知偏好
exports.ackIncident = callable(H.ackIncident);             // 「我來處理」

// ---- LINE webhook（在 LINE Developers 後台把 Webhook URL 設為這個函式的網址）
exports.lineWebhook = onRequest({ secrets: [LINE_TOKEN, LINE_SECRET] }, async (req, res) => {
  if (!C.verifyLineSignature(req.rawBody, req.get("x-line-signature"), LINE_SECRET.value())) { res.status(401).end(); return; }
  const replies = await H.lineWebhook(deps(), req.body && req.body.events);
  for (const r of replies) {
    await fetch("https://api.line.me/v2/bot/message/reply", {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${LINE_TOKEN.value()}` },
      body: JSON.stringify({ replyToken: r.replyToken, messages: [{ type: "text", text: r.text }] }),
    });
  }
  res.status(200).end();
});

// ---- 事件新增或更新 → 通知家屬
exports.onIncidentWritten = onDocumentWritten({ document: "devices/{deviceId}/incidents/{incidentId}", secrets: [LINE_TOKEN] }, async (event) => {
  const before = event.data.before.exists ? event.data.before.data() : null;
  const after = event.data.after.exists ? event.data.after.data() : null;
  const r = await H.onIncidentWritten(deps(), event.params.deviceId, before, after);
  if (r) logger.info("notified", event.params.incidentId, r.msg.level, r.results);
});

// ---- 每分鐘：還沒人接手的警報，3 分鐘到了就通知下一位家人
exports.escalate = onSchedule({ schedule: "every 1 minutes", timeZone: "Asia/Taipei", secrets: [LINE_TOKEN] }, async () => {
  const due = (await admin.firestore().collectionGroup("incidents").where("escNextAt", "<=", Date.now()).limit(200).get()).docs;
  const n = await H.escalateDue(deps(), due);
  if (n) logger.info("escalated", n);
});

// ---- 每 5 分鐘：機器人離線超過 30 分鐘就通知家人（只通知一次）
exports.offlineCheck = onSchedule({ schedule: "every 5 minutes", timeZone: "Asia/Taipei", secrets: [LINE_TOKEN] }, async () => {
  const stale = (await admin.firestore().collection("devices").where("lastSeen", "<", Date.now() - C.OFFLINE_MS).limit(500).get()).docs;
  const n = await H.notifyOffline(deps(), stale);
  if (n) logger.info("offline notified", n);
});

// ---- 每晚 9 點：平安摘要（家人自己在 App 開啟）
exports.dailySummary = onSchedule({ schedule: "0 21 * * *", timeZone: "Asia/Taipei", secrets: [LINE_TOKEN] }, async () => {
  const devices = (await admin.firestore().collection("devices").limit(2000).get()).docs;
  logger.info("daily summaries", await H.dailySummary(deps(), devices));
});

// ---- 每天凌晨 3 點清除超過保存期限的事件
exports.purgeExpired = onSchedule({ schedule: "0 3 * * *", timeZone: "Asia/Taipei" }, async () => {
  const n = await H.purgeExpired(deps(), async (cutoff) =>
    (await admin.firestore().collectionGroup("incidents").where("start", "<", cutoff).limit(500).get()).docs);
  logger.info("purged incidents", n);
});

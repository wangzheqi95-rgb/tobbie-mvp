"use strict";
/**
 * 純邏輯（不碰 Firebase），方便單元測試。
 */
const crypto = require("crypto");

const PAIRING_TTL_MS = 10 * 60 * 1000;      // 配對碼 10 分鐘有效
const RETENTION_DAYS = 90;                  // 事件照片與骨架預設保存 90 天
const OUTCOMES = ["PENDING", "CANCELLED", "ALERTED"];
const REASONS = ["LYING", "VANISH", "CHAT", "RADAR", "TEST"];
const ESCALATE_MS = 3 * 60 * 1000;          // 家人 3 分鐘沒按「我來處理」就通知下一位
const OFFLINE_MS = 30 * 60 * 1000;          // 機器人超過 30 分鐘沒心跳＝離線
const HR_KEEP_MS = 2 * 3600 * 1000;         // 心率樣本只保留最近 2 小時（給事件報告用）
const RELATIONS = ["兒子", "女兒", "配偶", "孫子女", "照顧者", "其他"];
/** 每位家屬的通知偏好預設值（緊急警報不能關）。 */
const DEFAULT_PREFS = { checking: true, cancelled: true, offline: true, daily: false, autoCall: true };

const sha256 = (s) => crypto.createHash("sha256").update(String(s)).digest("hex");
const newDeviceKey = () => crypto.randomBytes(24).toString("base64url");
const newPairingCode = () => String(crypto.randomInt(0, 1_000_000)).padStart(6, "0");
const newLinkCode = () => "N" + String(crypto.randomInt(0, 100_000_000)).padStart(8, "0");

/** 解析 `Authorization: Device <deviceId>:<deviceKey>`。 */
function parseDeviceAuth(header) {
  const m = /^Device ([A-Za-z0-9-]{8,64}):([A-Za-z0-9_-]{16,128})$/.exec(String(header || "").trim());
  return m ? { deviceId: m[1], deviceKey: m[2] } : null;
}

/** 固定時間比較，避免時間差攻擊。 */
function safeEqual(a, b) {
  const x = Buffer.from(String(a)), y = Buffer.from(String(b));
  return x.length === y.length && crypto.timingSafeEqual(x, y);
}

/** 檢查機器人上傳的事件（neara.incident.v1），回傳摘要（存 Firestore）與完整內容（存 Storage）。 */
function validateIncident(body, deviceId) {
  const errs = [];
  if (!body || typeof body !== "object") return { errs: ["內容不是 JSON 物件"] };
  if (body.schema !== "neara.incident.v1") errs.push("schema 必須是 neara.incident.v1");
  if (typeof body.id !== "string" || !/^[A-Za-z0-9_-]{6,80}$/.test(body.id)) errs.push("id 格式錯誤");
  if (body.deviceId !== deviceId) errs.push("deviceId 與驗證身分不符");
  if (!REASONS.includes(body.reason)) errs.push("reason 不合法");
  if (!OUTCOMES.includes(body.outcome)) errs.push("outcome 不合法");
  if (!Number.isFinite(body.start)) errs.push("start 必須是毫秒時間戳");
  for (const k of ["dialog", "pre", "post"]) if (body[k] !== undefined && !Array.isArray(body[k])) errs.push(`${k} 必須是陣列`);
  if (errs.length) return { errs };
  const summary = {
    id: body.id,
    deviceId,
    reason: body.reason,
    reasonLabel: String(body.reasonLabel || ""),
    start: body.start,
    lastWell: Number.isFinite(body.lastWell) ? body.lastWell : null,
    outcome: body.outcome,
    outcomeLabel: String(body.outcomeLabel || ""),
    end: Number.isFinite(body.end) ? body.end : null,
    resolved: Number.isFinite(body.resolved) ? body.resolved : null,
    resolvedBy: body.resolvedBy ? String(body.resolvedBy).slice(0, 80) : null,
    chassis: String(body.chassis || ""),
    dialog: (body.dialog || []).slice(0, 50).map((d) => ({ t: Number(d.t) || 0, who: String(d.who || "").slice(0, 40), text: String(d.text || "").slice(0, 500) })),
  };
  const full = { ...body };
  delete full.snapshotJpegBase64;
  return { errs, summary, full };
}

/** 依事件狀態決定要發什麼通知（null = 不通知）。 */
function notificationFor(prev, next, elderName) {
  const who = elderName || "長者";
  const was = prev ? prev.outcome : null;
  if (!prev) {
    return { level: "info", title: "機器人正在確認狀況", body: `偵測到${who}可能跌倒（${next.reasonLabel || next.reason}），正在詢問中。` };
  }
  if (was !== "ALERTED" && next.outcome === "ALERTED") {
    return { level: "critical", title: `緊急：${who}可能跌倒且沒有回應`, body: "請立即聯絡或前往查看。守護中心已同步收到。" };
  }
  if (was !== "CANCELLED" && next.outcome === "CANCELLED") {
    return { level: "info", title: `${who}表示沒事`, body: "機器人已確認，事件已取消。" };
  }
  if (!prev.resolved && next.resolved) {
    return { level: "info", title: "警報已解除", body: `由 ${next.resolvedBy || "現場"} 解除。` };
  }
  return null;
}

/** 保存期限：早於這個時間的事件要刪。 */
const retentionCutoff = (now, days = RETENTION_DAYS) => now - days * 24 * 3600 * 1000;

/** LINE webhook 簽章驗證（HMAC-SHA256，base64）。 */
function verifyLineSignature(rawBody, signature, channelSecret) {
  if (!signature || !channelSecret) return false;
  const mac = crypto.createHmac("sha256", channelSecret).update(rawBody).digest("base64");
  return safeEqual(mac, signature);
}

/** AI 聊天系統提示（與機器人端 Lines.systemPrompt 一致）。 */
function systemPrompt(name) {
  return `你是一台住在長輩家裡的居家陪伴機器人，正在跟「${name}」聊天。
請用台灣日常口語的中文，語氣溫暖親切，像孫子孫女在聊天。
每次回覆一到兩句、不超過 40 個字，常常用一個簡單的問題結尾，讓長輩願意繼續說。
不要使用表情符號、條列、英文或括號說明。
不要做醫療診斷；如果長輩說身體不舒服，溫柔關心，並建議告訴家人或去看醫生。`;
}

/** 角色：family 家屬、guardian 守護中心、admin 管理員。 */
function canSeeDevice(user, device) {
  if (!user || !device) return false;
  if (user.role === "admin" || user.role === "guardian") return true;
  return Array.isArray(device.members) && device.members.includes(user.uid);
}

// ------------------------------------------------------------------ 監護人與升級
const prefsOf = (user) => ({ ...DEFAULT_PREFS, ...((user && user.notifyPrefs) || {}) });

/** 監護人通知順序：guardianOrder 裡有的照順序，其他成員接在後面（舊資料相容）。 */
function guardianOrder(device) {
  const members = (device && device.members) || [];
  const order = ((device && device.guardianOrder) || []).filter((u) => members.includes(u));
  return [...new Set([...order, ...members])];
}

/** 守護中心要不要同步收到：試用期間（plan 未設定或 trial）與守護方案都要；只有 basic 方案不通知。 */
const centerEnabled = (device) => ((device && device.plan) || "trial") !== "basic";

/**
 * 升級規則（簡單版）：
 *   警報開始（level 0）→ 守護中心＋第 1 位家人同時通知；
 *   每過 3 分鐘沒人按「我來處理」→ 通知下一位家人（level 1、2…）；
 *   家人都通知過了就不再排下一次。
 * 回傳這一步要通知誰、下一次什麼時候再檢查。
 */
function escalationStep(order, level, now) {
  const who = order[level] || null;
  const nextAt = level + 1 < order.length ? now + ESCALATE_MS : null;
  return { who, nextAt };
}

/** 從心率樣本找事件前後的數字：事件前 10 分鐘內最後一筆、事件後 5 分鐘內最高的一筆。 */
function vitalsAround(samples, start) {
  const hr = (samples || []).filter((s) => Number.isFinite(s.t) && Number.isFinite(s.bpm));
  const before = hr.filter((s) => s.t <= start && s.t >= start - 10 * 60 * 1000).sort((a, b) => a.t - b.t).pop();
  const after = hr.filter((s) => s.t > start && s.t <= start + 5 * 60 * 1000).sort((a, b) => b.bpm - a.bpm)[0];
  if (!before && !after) return null;
  return { hrBefore: before ? before.bpm : null, hrAfter: after ? after.bpm : null, source: "手錶（Health Connect）" };
}

/** 合併新上傳的心率樣本：去重、排序、只留最近 2 小時、最多 720 筆。 */
function mergeHeartRate(old, incoming, now) {
  const map = new Map();
  for (const s of [...(old || []), ...(incoming || [])]) {
    const t = Number(s.t), bpm = Number(s.bpm);
    if (!Number.isFinite(t) || !Number.isFinite(bpm) || bpm < 20 || bpm > 250) continue;
    map.set(t, { t, bpm: Math.round(bpm) });
  }
  return [...map.values()].filter((s) => s.t >= now - HR_KEEP_MS && s.t <= now + 60000).sort((a, b) => a.t - b.t).slice(-720);
}

/** 台灣時間的「今天 0 點」（毫秒）。 */
function taipeiDayStart(now) {
  const off = 8 * 3600 * 1000;
  return Math.floor((now + off) / 86400000) * 86400000 - off;
}

/** 電話號碼：台灣手機或市話，存成只有數字與 + 的格式。 */
function normalizePhone(p) {
  const s = String(p || "").replace(/[\s-]/g, "");
  return /^\+?\d{8,15}$/.test(s) ? s : null;
}

module.exports = {
  ESCALATE_MS, OFFLINE_MS, RELATIONS, DEFAULT_PREFS, prefsOf, guardianOrder, centerEnabled, escalationStep,
  vitalsAround, mergeHeartRate, taipeiDayStart, normalizePhone,
  PAIRING_TTL_MS, RETENTION_DAYS, sha256, newDeviceKey, newPairingCode, newLinkCode,
  parseDeviceAuth, safeEqual, validateIncident, notificationFor, retentionCutoff,
  verifyLineSignature, systemPrompt, canSeeDevice,
};

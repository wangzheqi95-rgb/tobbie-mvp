// Neara 守護中心後台：即時事件清單 → 詳情（照片、問答、骨架重播、長者資料）→ 結案。
// 資料來源有兩種：Firebase（正式）與 Demo（網址加 ?demo=1，不需登入，用於展示與測試）。
const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const fmt = (t) => (t ? new Date(t).toLocaleString("zh-TW", { hour12: false }) : "—");
const ago = (t) => { if (!t) return "—"; const s = Math.round((Date.now() - t) / 1000); return s < 60 ? `${s} 秒前` : s < 3600 ? `${Math.round(s / 60)} 分鐘前` : `${Math.round(s / 3600)} 小時前`; };
const CONN = [[11,12],[11,13],[13,15],[12,14],[14,16],[11,23],[12,24],[23,24],[23,25],[25,27],[24,26],[26,28],[0,11],[0,12]];

// ------------------------------------------------------------------ 資料來源
async function firebaseSource() {
  const V = "10.14.1";
  const [{ initializeApp }, A, F, S, Fn] = await Promise.all([
    import(`https://www.gstatic.com/firebasejs/${V}/firebase-app.js`),
    import(`https://www.gstatic.com/firebasejs/${V}/firebase-auth.js`),
    import(`https://www.gstatic.com/firebasejs/${V}/firebase-firestore.js`),
    import(`https://www.gstatic.com/firebasejs/${V}/firebase-storage.js`),
    import(`https://www.gstatic.com/firebasejs/${V}/firebase-functions.js`),
  ]);
  const app = initializeApp(window.NEARA_FIREBASE);
  const auth = A.getAuth(app), db = F.getFirestore(app), st = S.getStorage(app), fns = Fn.getFunctions(app, window.NEARA_REGION || "asia-east1");
  return {
    onUser: (cb) => A.onAuthStateChanged(auth, async (u) => {
      if (!u) return cb(null);
      const prof = await F.getDoc(F.doc(db, "users", u.uid));
      cb({ uid: u.uid, email: u.email, ...(prof.exists() ? prof.data() : {}) });
    }),
    login: (e, p) => A.signInWithEmailAndPassword(auth, e, p),
    logout: () => A.signOut(auth),
    watchIncidents: (cb) => F.onSnapshot(F.query(F.collectionGroup(db, "incidents"), F.orderBy("start", "desc"), F.limit(100)),
      (snap) => cb(snap.docs.map((d) => ({ ...d.data(), _deviceId: d.ref.parent.parent.id }))), (e) => alert("讀取事件失敗：" + e.message)),
    getDevice: async (id) => { const s = await F.getDoc(F.doc(db, "devices", id)); return s.exists() ? s.data() : null; },
    getNotifications: async (devId, incId) => (await F.getDocs(F.query(F.collection(db, "devices", devId, "incidents", incId, "notifications"), F.orderBy("at")))).docs.map((d) => d.data()),
    url: (path) => (path ? S.getDownloadURL(S.ref(st, path)) : null),
    detail: async (path) => (path ? (await fetch(await S.getDownloadURL(S.ref(st, path)))).json() : null),
    resolve: (deviceId, incidentId, note) => Fn.httpsCallable(fns, "resolveIncident")({ deviceId, incidentId, note }),
  };
}

function demoSource() {
  const now = Date.now();
  const lie = (i) => Array.from({ length: 33 }, (_, k) => [0.3 + (k % 5) * 0.02 + i * 0.001, 0.72 + (k % 3) * 0.02]);
  const stand = Array.from({ length: 33 }, (_, k) => [0.45 + (k % 2) * 0.05, 0.15 + k * 0.022]);
  const frames = [...Array(60)].map((_, i) => ({ t: now - 120000 - (60 - i) * 100, deg: i < 40 ? 8 : 85, p: i < 40 ? stand : lie(i) }));
  const devices = {
    "dev-a": { elderName: "王阿嬤", profile: "82 歲，獨居。\n慣用藥物：降血壓藥（早上一顆）\n過敏：無\n緊急聯絡人：兒子 王大明 0912-000-000", lastSeen: now - 30000, state: "ALERT", chassis: "石頭 S6", members: ["u1"] },
    "dev-b": { elderName: "陳阿公", profile: "79 歲，與太太同住。\n緊急聯絡人：女兒 0922-000-000", lastSeen: now - 50000, state: "MONITOR", chassis: "Tobbie II", members: ["u2"] },
  };
  let incidents = [
    { id: "NEARA-dev-a-1", _deviceId: "dev-a", elderName: "王阿嬤", reason: "LYING", reasonLabel: "偵測到疑似跌倒", start: now - 120000, lastWell: now - 124000, outcome: "ALERTED", outcomeLabel: "無回應或求救，已發出警報", resolved: null, chassis: "石頭 S6",
      dialog: [{ t: now - 115000, who: "機器人", text: "阿嬤，您還好嗎？是不是跌倒了？" }, { t: now - 107000, who: "阿嬤", text: "（沒有回應）　→ 判讀：無法判斷" }, { t: now - 106000, who: "機器人", text: "阿嬤，聽得到我嗎？沒事的話，跟我說「沒事」就好喔。" }, { t: now - 98000, who: "阿嬤", text: "好痛…起不來　→ 判讀：求救" }] },
    { id: "NEARA-dev-b-1", _deviceId: "dev-b", elderName: "陳阿公", reason: "VANISH", reasonLabel: "長者往下掉後消失在畫面中", start: now - 3600_000, lastWell: now - 3604_000, outcome: "CANCELLED", outcomeLabel: "長者表示沒事，已取消", resolved: null, chassis: "Tobbie II",
      dialog: [{ t: now - 3598_000, who: "機器人", text: "阿公，我突然看不到您了，您還好嗎？" }, { t: now - 3592_000, who: "阿公", text: "我沒事啦　→ 判讀：沒事" }] },
  ];
  let watcher = null;
  return {
    onUser: (cb) => setTimeout(() => cb({ uid: "demo", email: "demo@neara.tw", role: "guardian", displayName: "展示值班員" }), 0),
    login: async () => {}, logout: async () => location.reload(),
    watchIncidents: (cb) => { watcher = cb; cb(incidents); },
    getDevice: async (id) => devices[id],
    getNotifications: async (d, i) => (i === "NEARA-dev-a-1" ? [{ at: now - 119000, level: "info", title: "機器人正在確認狀況" }, { at: now - 97000, level: "critical", title: "緊急：王阿嬤可能跌倒且沒有回應", pushTokens: 2, lineUsers: 1 }] : []),
    url: async () => null,
    detail: async () => ({ frameW: 640, frameH: 480, pre: frames.slice(0, 45), post: frames.slice(45) }),
    resolve: async (deviceId, incidentId, note) => {
      incidents = incidents.map((x) => (x.id === incidentId ? { ...x, resolved: Date.now(), resolvedBy: "守護中心 展示值班員", resolveNote: note } : x));
      watcher && watcher(incidents);
    },
  };
}

// ------------------------------------------------------------------ 畫面
let src, items = [], selected = null, soundOn = false, audioCtx = null, beeping = null;
const isOpenAlert = (x) => x.outcome === "ALERTED" && !x.resolved;
const tagOf = (x) => isOpenAlert(x) ? ["t-alert", "警報中"] : x.resolved ? ["t-done", "已結案"] : x.outcome === "CANCELLED" ? ["t-ok", "已取消"] : ["t-pending", "確認中"];

function renderList() {
  const open = items.filter(isOpenAlert).length;
  $("count").textContent = `${items.length} 筆｜警報中 ${open}`;
  const b = $("banner");
  b.style.display = open ? "block" : "none";
  b.textContent = open ? `⚠ 有 ${open} 筆警報尚未處理` : "";
  setBeep(open > 0);
  if (!items.length) { $("list").innerHTML = '<div class="empty">目前沒有事件</div>'; return; }
  const sorted = [...items].sort((a, b) => (isOpenAlert(b) - isOpenAlert(a)) || (b.start - a.start));
  $("list").innerHTML = sorted.map((x) => {
    const [cls, label] = tagOf(x);
    return `<div class="item ${selected && selected.id === x.id ? "sel" : ""}" data-id="${esc(x.id)}">
      <div class="top"><span>${esc(x.elderName || x._deviceId)}</span><span class="tag ${cls}">${label}</span></div>
      <div class="sub">${esc(x.reasonLabel || x.reason)}｜${fmt(x.start)}（${ago(x.start)}）</div></div>`;
  }).join("");
  document.querySelectorAll(".item").forEach((el) => (el.onclick = () => select(items.find((x) => x.id === el.dataset.id))));
}

async function select(x) {
  selected = x; renderList();
  const d = $("detail");
  d.innerHTML = '<div class="empty">載入中…</div>';
  const [dev, notes, snapUrl, det] = await Promise.all([
    src.getDevice(x._deviceId), src.getNotifications(x._deviceId, x.id).catch(() => []),
    src.url(x.snapshotPath).catch(() => null), src.detail(x.detailPath).catch(() => null),
  ]);
  if (selected !== x) return;
  const online = dev && dev.lastSeen && Date.now() - dev.lastSeen < 3 * 60_000;
  $("devStatus").innerHTML = dev ? `機器人 ${online ? "🟢 在線" : "🔴 離線"}（最後回報 ${ago(dev.lastSeen)}）` : "";
  const [cls, label] = tagOf(x);
  const gap = x.lastWell ? Math.round((x.start - x.lastWell) / 1000) : null;
  d.innerHTML = `
    <div class="row"><span class="tag ${cls}" style="font-size:14px">${label}</span><b style="font-size:18px">${esc(x.elderName || "")}</b><span class="muted">${esc(x.id)}</span></div>
    <h3>事件摘要</h3>
    <table>
      <tr><td>事件時間</td><td>${fmt(x.start)}（${ago(x.start)}）</td></tr>
      <tr><td>偵測原因</td><td>${esc(x.reasonLabel || x.reason)}</td></tr>
      <tr><td>最後正常活動</td><td>${x.lastWell ? `${fmt(x.lastWell)}（事件前約 ${gap < 60 ? gap + " 秒" : Math.round(gap / 60) + " 分鐘"}）` : "—"}</td></tr>
      <tr><td>處理結果</td><td>${esc(x.outcomeLabel || x.outcome)}</td></tr>
      <tr><td>結案</td><td>${x.resolved ? `${fmt(x.resolved)}｜${esc(x.resolvedBy || "")}${x.resolveNote ? `<br>${esc(x.resolveNote)}` : ""}` : "尚未結案"}</td></tr>
      <tr><td>底盤</td><td>${esc(x.chassis || "")}</td></tr>
    </table>
    <h3>長者資料與緊急聯絡人</h3>
    <div style="white-space:pre-wrap">${esc((dev && dev.profile) || "（家屬尚未填寫）")}</div>
    ${snapUrl ? `<h3>事件當下照片</h3><img class="snap" src="${esc(snapUrl)}" alt="事件照片">` : ""}
    <h3>機器人與長者的問答</h3>
    <table>${(x.dialog || []).map((l) => `<tr><td>${new Date(l.t).toLocaleTimeString("zh-TW", { hour12: false })}｜${esc(l.who)}</td><td>${esc(l.text)}</td></tr>`).join("") || "<tr><td colspan=2>（無）</td></tr>"}</table>
    <h3>動作重播（骨架，事件前 60 秒～後 120 秒）</h3>
    ${det && (det.pre || det.post) ? `<canvas id="cv" width="${det.frameW || 640}" height="${det.frameH || 480}"></canvas>
      <div class="row"><button id="play">播放</button><input id="sl" type="range" min="0" value="0" style="flex:1"><span id="lab" class="muted"></span></div>` : '<div class="muted">（沒有骨架資料）</div>'}
    <h3>通知紀錄</h3>
    <table>${notes.map((n) => `<tr><td>${fmt(n.at)}</td><td>${n.level === "critical" ? "🔴 " : ""}${esc(n.title)}${n.pushTokens != null ? `<span class="muted">（推播 ${n.pushTokens}、LINE ${n.lineUsers}）</span>` : ""}</td></tr>`).join("") || "<tr><td colspan=2>（無）</td></tr>"}</table>
    ${x.resolved ? "" : `<h3>結案</h3>
      <textarea id="note" placeholder="處理經過，例如：已電話聯絡兒子，10 分鐘內到場；或已通報 119"></textarea>
      <div class="row" style="margin-top:8px"><button class="danger" id="btnResolve">確認結案（機器人會停止警報）</button></div>`}`;
  if (det && (det.pre || det.post)) replay(det, x.start);
  const btn = $("btnResolve");
  if (btn) btn.onclick = async () => {
    const note = $("note").value.trim();
    if (!note) { alert("請填寫處理經過"); return; }
    btn.disabled = true;
    try { await src.resolve(x._deviceId, x.id, note); } catch (e) { alert("結案失敗：" + e.message); btn.disabled = false; }
  };
}

function replay(det, start) {
  const F = [...(det.pre || []), ...(det.post || [])];
  const cv = $("cv"), g = cv.getContext("2d"), sl = $("sl"), lab = $("lab");
  sl.max = Math.max(F.length - 1, 0);
  const show = (i) => {
    const f = F[i]; g.fillStyle = "#0b0d10"; g.fillRect(0, 0, cv.width, cv.height);
    if (!f) return;
    if (f.p) { g.strokeStyle = f.t < start ? "#4f8cff" : "#ff6b5b"; g.lineWidth = 4;
      for (const [a, b] of CONN) if (f.p[a] && f.p[b]) { g.beginPath(); g.moveTo(f.p[a][0] * cv.width, f.p[a][1] * cv.height); g.lineTo(f.p[b][0] * cv.width, f.p[b][1] * cv.height); g.stroke(); } }
    const rel = (f.t - start) / 1000;
    lab.textContent = `${rel < 0 ? "事件前" : "事件後"} ${Math.abs(rel).toFixed(1)} 秒｜${f.p ? `軀幹 ${f.deg}°` : "看不到人"}`;
  };
  let tm = null;
  $("play").onclick = () => { if (tm) { clearInterval(tm); tm = null; return; } tm = setInterval(() => { const i = +sl.value + 1; if (i >= F.length) { clearInterval(tm); tm = null; return; } sl.value = i; show(i); }, 100); };
  sl.oninput = () => show(+sl.value);
  const z = F.findIndex((f) => f.t >= start); sl.value = Math.max(0, (z < 0 ? 0 : z) - 20); show(+sl.value);
}

// 警報音：瀏覽器規定要先點一下才能出聲，所以有「開啟警報音」按鈕
function setBeep(on) {
  if (on && soundOn && !beeping) {
    beeping = setInterval(() => { const o = audioCtx.createOscillator(), gn = audioCtx.createGain(); o.frequency.value = 880; gn.gain.value = 0.2; o.connect(gn).connect(audioCtx.destination); o.start(); o.stop(audioCtx.currentTime + 0.3); }, 900);
  } else if ((!on || !soundOn) && beeping) { clearInterval(beeping); beeping = null; }
}

async function main() {
  const demo = new URLSearchParams(location.search).has("demo");
  if (!demo && !(window.NEARA_FIREBASE && window.NEARA_FIREBASE.apiKey)) {
    document.body.innerHTML = '<div class="empty">尚未設定 Firebase：請把 config.example.js 複製成 config.js 並填入專案設定。<br>想先看畫面可在網址後加 <b>?demo=1</b>。</div>';
    return;
  }
  src = demo ? demoSource() : await firebaseSource();
  setInterval(() => ($("clock").textContent = new Date().toLocaleTimeString("zh-TW", { hour12: false })), 1000);
  $("btnSound").onclick = () => { audioCtx = audioCtx || new AudioContext(); soundOn = !soundOn; $("btnSound").textContent = soundOn ? "關閉警報音" : "開啟警報音"; renderList(); };
  $("btnLogout").onclick = () => src.logout();
  $("btnLogin").onclick = async () => { try { await src.login($("email").value, $("pw").value); } catch (e) { $("loginMsg").textContent = "登入失敗：" + e.message; } };
  let unsub = null;
  src.onUser((u) => {
    if (unsub) { unsub(); unsub = null; }
    if (!u) { $("app").style.display = "none"; $("login").style.display = "block"; return; }
    if (!["guardian", "admin"].includes(u.role)) { $("login").style.display = "block"; $("app").style.display = "none"; $("loginMsg").textContent = "這個帳號沒有守護中心權限，請聯絡管理員。"; src.logout(); return; }
    $("login").style.display = "none"; $("app").style.display = "block";
    $("who").textContent = `${u.displayName || u.email}（${u.role === "admin" ? "管理員" : "守護中心"}）`;
    unsub = src.watchIncidents((list) => {
      items = list;
      if (selected) { const s = items.find((x) => x.id === selected.id); if (s && s.resolved !== selected.resolved) select(s); else selected = s || selected; }
      renderList();
    }) || null;
  });
}
main();

#!/usr/bin/env node
/**
 * 管理員工具：把某個帳號設成守護中心人員或管理員（前端無法自己改角色）。
 *   GOOGLE_APPLICATION_CREDENTIALS=服務帳戶金鑰.json node scripts/set-role.js 使用者Email guardian 值班員小林
 */
const admin = require("../functions/node_modules/firebase-admin");
admin.initializeApp();
(async () => {
  const [email, role, displayName] = process.argv.slice(2);
  if (!email || !["family", "guardian", "admin"].includes(role)) {
    console.error("用法：node scripts/set-role.js <email> <family|guardian|admin> [顯示名稱]"); process.exit(1);
  }
  const user = await admin.auth().getUserByEmail(email);
  await admin.firestore().collection("users").doc(user.uid).set({ role, ...(displayName ? { displayName } : {}) }, { merge: true });
  console.log(`已將 ${email}（${user.uid}）設為 ${role}`);
})();

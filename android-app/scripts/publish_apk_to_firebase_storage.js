const crypto = require("crypto");
const fs = require("fs");
const path = require("path");

const apkPath = process.argv[2];
const bucket = process.argv[3] || "school-65f1a.firebasestorage.app";

if (!apkPath || !fs.existsSync(apkPath)) {
  throw new Error("Usage: node scripts/publish_apk_to_firebase_storage.js <apk-path> [bucket]");
}

const configPath = path.join(process.env.USERPROFILE, ".config", "configstore", "firebase-tools.json");
const config = JSON.parse(fs.readFileSync(configPath, "utf8"));
const accessToken = config.tokens?.access_token;
if (!accessToken) {
  throw new Error("Firebase CLI credentials are not available. Run firebase login first.");
}

const fileName = `school-management-1.5.0-${new Date().toISOString().slice(0, 10)}.apk`;
const objectName = `releases/${fileName}`;
const downloadToken = crypto.randomUUID();
const uploadUrl = `https://firebasestorage.googleapis.com/v0/b/${bucket}/o?name=${encodeURIComponent(objectName)}`;

async function publish() {
  const response = await fetch(uploadUrl, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${accessToken}`,
      "Content-Type": "application/vnd.android.package-archive",
      "X-Goog-Meta-firebaseStorageDownloadTokens": downloadToken,
    },
    body: fs.readFileSync(apkPath),
  });

  if (!response.ok) {
    throw new Error(`Upload failed with HTTP ${response.status}: ${await response.text()}`);
  }

  console.log(
    `https://firebasestorage.googleapis.com/v0/b/${bucket}/o/${encodeURIComponent(objectName)}?alt=media&token=${downloadToken}`,
  );
}

publish().catch((error) => {
  console.error(error.message);
  process.exit(1);
});

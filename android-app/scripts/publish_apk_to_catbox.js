const fs = require("fs");
const path = require("path");

const apkPath = process.argv[2];
if (!apkPath || !fs.existsSync(apkPath)) {
  throw new Error("Usage: node scripts/publish_apk_to_catbox.js <apk-path>");
}

async function publish() {
  const form = new FormData();
  form.append("reqtype", "fileupload");
  form.append(
    "fileToUpload",
    new Blob([fs.readFileSync(apkPath)], { type: "application/vnd.android.package-archive" }),
    path.basename(apkPath),
  );

  const response = await fetch("https://catbox.moe/user/api.php", { method: "POST", body: form });
  const link = (await response.text()).trim();
  if (!response.ok || !link.startsWith("https://")) {
    throw new Error(`Upload failed with HTTP ${response.status}: ${link}`);
  }
  console.log(link);
}

publish().catch((error) => {
  console.error(error.message);
  process.exit(1);
});

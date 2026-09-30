import re
import os
import sys
import json
import urllib.request
import urllib.error
import subprocess
from datetime import datetime

# Fix Windows stdout encoding
if sys.stdout and hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')

VERSION_NAME = "1.2.0"
VERSION_CODE = 3
IS_FORCE_UPDATE = True
REPO = "autoclicker631/auto-clicker-app"

RELEASE_NOTES = (
    "• إصلاحات شاملة لنظام التحديث الإجباري ومنع التخطي\n"
    "• الاحتفاظ بملف التحديث المكتمل لتجنب إعادة التنزيل من جديد\n"
    "• حذف ملف التحديث تلقائياً بعد التثبيت لتوفير مساحة التخزين\n"
    "• تحسين استقرار وسرعة استجابة الأداة العائمة"
)

# 1. Update app/build.gradle.kts
print("[1/5] Updating app/build.gradle.kts...")
with open("app/build.gradle.kts", "r", encoding="utf-8") as f:
    content = f.read()

content = re.sub(r'versionCode\s*=\s*\d+', f'versionCode = {VERSION_CODE}', content)
content = re.sub(r'versionName\s*=\s*"[^"]+"', f'versionName = "{VERSION_NAME}"', content)

with open("app/build.gradle.kts", "w", encoding="utf-8") as f:
    f.write(content)
print("-> Updated app/build.gradle.kts")

# Read token
token_path = "github_token.txt"
if not os.path.exists(token_path):
    print("Error: github_token.txt not found!")
    sys.exit(1)

with open(token_path, "r", encoding="utf-8") as f:
    token = f.read().strip()

# 2. Get or Create GitHub Release
print("[2/5] Checking/Creating GitHub Release v" + VERSION_NAME + "...")
headers = {
    "Authorization": f"token {token}",
    "Accept": "application/vnd.github.v3+json",
    "User-Agent": "AutoClickerPublisher"
}

release_res = None
tag_url = f"https://api.github.com/repos/{REPO}/releases/tags/v{VERSION_NAME}"
req_get = urllib.request.Request(tag_url, headers=headers)

try:
    with urllib.request.urlopen(req_get) as resp:
        release_res = json.loads(resp.read().decode('utf-8'))
        print("-> Found existing release tag v" + VERSION_NAME)
except urllib.error.HTTPError as e:
    if e.code == 404:
        # Create new release
        print("-> Creating new release tag v" + VERSION_NAME)
        release_payload = {
            "tag_name": f"v{VERSION_NAME}",
            "target_commitish": "main",
            "name": f"v{VERSION_NAME} (تحديث إجباري)",
            "body": RELEASE_NOTES,
            "draft": False,
            "prerelease": False
        }
        create_req = urllib.request.Request(
            f"https://api.github.com/repos/{REPO}/releases",
            data=json.dumps(release_payload).encode('utf-8'),
            headers={**headers, "Content-Type": "application/json; charset=utf-8"},
            method="POST"
        )
        with urllib.request.urlopen(create_req) as resp:
            release_res = json.loads(resp.read().decode('utf-8'))
    else:
        err_body = e.read().decode('utf-8')
        print(f"Error checking release: {e.code} {err_body}")
        sys.exit(1)

upload_url_template = release_res["upload_url"]
upload_url = upload_url_template.split("{")[0] + f"?name=AutoClicker-v{VERSION_NAME}.apk"

# Delete existing asset if it exists
apk_asset_name = f"AutoClicker-v{VERSION_NAME}.apk"
for asset in release_res.get("assets", []):
    if asset["name"] == apk_asset_name:
        print(f"-> Deleting old asset {asset['id']}...")
        del_req = urllib.request.Request(
            asset["url"],
            headers=headers,
            method="DELETE"
        )
        try:
            with urllib.request.urlopen(del_req) as resp:
                pass
        except Exception as ex:
            print(f"Warning deleting old asset: {ex}")

# 3. Upload APK Asset using curl.exe
print("[3/5] Uploading APK asset using curl.exe...")
apk_path = "app/build/outputs/apk/release/app-release.apk"
if not os.path.exists(apk_path):
    print("Error: APK not found at " + apk_path)
    sys.exit(1)

curl_cmd = [
    "curl.exe",
    "-s",
    "-X", "POST",
    "-H", f"Authorization: token {token}",
    "-H", "Content-Type: application/vnd.android.package-archive",
    "--data-binary", f"@{apk_path}",
    upload_url
]

curl_res = subprocess.run(curl_cmd, capture_output=True, text=True)
if curl_res.returncode != 0:
    print(f"Error: curl upload failed: {curl_res.stderr}")
    sys.exit(1)

try:
    upload_json = json.loads(curl_res.stdout)
    download_url = upload_json["browser_download_url"]
    print(f"-> Uploaded successfully! Download URL: {download_url}")
except Exception as e:
    print(f"Error parsing curl response: {e}\nOutput: {curl_res.stdout[:500]}")
    sys.exit(1)

# 4. Update version.json
print("[4/5] Updating version.json...")
version_info = {
    "latestVersionCode": VERSION_CODE,
    "latestVersionName": VERSION_NAME,
    "minSupportedVersionCode": VERSION_CODE if IS_FORCE_UPDATE else 1,
    "isForceUpdate": IS_FORCE_UPDATE,
    "title": f"تحديث جديد متوفر v{VERSION_NAME} 🚀",
    "releaseNotes": RELEASE_NOTES,
    "downloadUrl": download_url,
    "publishDate": datetime.now().strftime("%Y-%m-%d")
}

with open("version.json", "w", encoding="utf-8") as f:
    json.dump(version_info, f, ensure_ascii=False, indent=2)
print("-> Updated version.json")

# 5. Git commit and push
print("[5/5] Committing and pushing to GitHub...")
subprocess.run(["git", "add", "."], check=True)
subprocess.run(["git", "commit", "-m", f"Publish version v{VERSION_NAME} (Code: {VERSION_CODE}) - Forced Update"], check=True)
subprocess.run(["git", "push", "origin", "main"], check=True)

print("==========================================")
print(f"Successfully published v{VERSION_NAME} (Forced Update) to GitHub!")
print("==========================================")

import argparse
import os
import sys
import json
import re
import urllib.request
import urllib.error
import subprocess
from datetime import datetime

# Fix Windows stdout encoding
if sys.stdout and hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')

REPO = "autoclicker631/auto-clicker-app"

def get_current_version():
    if os.path.exists("version.json"):
        try:
            with open("version.json", "r", encoding="utf-8") as f:
                data = json.load(f)
                return data.get("latestVersionName", "1.0.0"), data.get("latestVersionCode", 1)
        except Exception:
            pass
    return "1.0.0", 1

def auto_increment_version(ver_name, ver_code):
    parts = ver_name.split(".")
    if len(parts) == 3:
        parts[2] = str(int(parts[2]) + 1)
        new_name = ".".join(parts)
    elif len(parts) == 2:
        parts[1] = str(int(parts[1]) + 1)
        new_name = ".".join(parts)
    else:
        new_name = f"{ver_name}.1"
    new_code = ver_code + 1
    return new_name, new_code

def main():
    curr_name, curr_code = get_current_version()
    next_name, next_code = auto_increment_version(curr_name, curr_code)

    parser = argparse.ArgumentParser(description="Publish AutoClicker release to GitHub")
    parser.add_argument("-v", "--version", default=None, help=f"Version name (default: next auto version {next_name})")
    parser.add_argument("-c", "--code", type=int, default=None, help=f"Version code (default: next code {next_code})")
    parser.add_argument("-f", "--force", action="store_true", default=True, help="Set as Forced Update (default: True)")
    parser.add_argument("--optional", dest="force", action="store_false", help="Set as Optional Update")
    parser.add_argument("-n", "--notes", default="• تحسينات عامة وإصلاحات في الأداء.", help="Release notes in Arabic/English")
    parser.add_argument("--skip-build", action="store_true", help="Skip running assembleRelease")

    args = parser.parse_args()

    version_name = args.version or next_name
    version_code = args.code or next_code
    is_force = args.force
    release_notes = args.notes

    print("==========================================")
    print(f"🚀 Publishing AutoClicker v{version_name} (Code: {version_code})")
    print(f"📌 Type: {'🔴 Force Update' if is_force else '🟢 Optional Update'}")
    print("==========================================")

    # 1. Update app/build.gradle.kts
    print("[1/5] Updating app/build.gradle.kts...")
    with open("app/build.gradle.kts", "r", encoding="utf-8") as f:
        content = f.read()

    content = re.sub(r'versionCode\s*=\s*\d+', f'versionCode = {version_code}', content)
    content = re.sub(r'versionName\s*=\s*"[^"]+"', f'versionName = "{version_name}"', content)

    with open("app/build.gradle.kts", "w", encoding="utf-8") as f:
        f.write(content)
    print("-> Updated app/build.gradle.kts")

    # 2. Build Release APK
    if not args.skip_build:
        print("[2/5] Building Release APK (gradlew assembleRelease)...")
        res = subprocess.run(["cmd.exe", "/c", "gradlew.bat", "assembleRelease"])
        if res.returncode != 0:
            print("❌ Gradle build failed!")
            sys.exit(1)
        print("-> Build completed successfully!")
    else:
        print("[2/5] Skipping build (--skip-build specified)...")

    # Read token
    token_path = "github_token.txt"
    if not os.path.exists(token_path):
        print("❌ Error: github_token.txt not found!")
        sys.exit(1)

    with open(token_path, "r", encoding="utf-8") as f:
        token = f.read().strip()

    headers = {
        "Authorization": f"token {token}",
        "Accept": "application/vnd.github.v3+json",
        "User-Agent": "AutoClickerPublisher"
    }

    # 3. Create or Get GitHub Release
    print(f"[3/5] Creating GitHub Release v{version_name}...")
    tag_url = f"https://api.github.com/repos/{REPO}/releases/tags/v{version_name}"
    req_get = urllib.request.Request(tag_url, headers=headers)
    release_res = None

    try:
        with urllib.request.urlopen(req_get) as resp:
            release_res = json.loads(resp.read().decode('utf-8'))
            print("-> Found existing release tag v" + version_name)
    except urllib.error.HTTPError as e:
        if e.code == 404:
            # Create new release
            print("-> Creating new release tag v" + version_name)
            release_payload = {
                "tag_name": f"v{version_name}",
                "target_commitish": "main",
                "name": f"v{version_name} ({'تحديث إجباري' if is_force else 'تحديث اختياري'})",
                "body": release_notes,
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
    upload_url = upload_url_template.split("{")[0] + f"?name=AutoClicker-v{version_name}.apk"

    # Delete existing asset if it exists
    apk_asset_name = f"AutoClicker-v{version_name}.apk"
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

    # 4. Upload APK Asset using curl.exe
    print("[4/5] Uploading APK asset...")
    apk_path = "app/build/outputs/apk/release/app-release.apk"
    if not os.path.exists(apk_path):
        print("❌ Error: APK not found at " + apk_path)
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
        print(f"❌ Error: curl upload failed: {curl_res.stderr}")
        sys.exit(1)

    try:
        upload_json = json.loads(curl_res.stdout)
        download_url = upload_json["browser_download_url"]
        print(f"-> Uploaded successfully! Direct Download: {download_url}")
    except Exception as e:
        print(f"❌ Error parsing curl response: {e}\nOutput: {curl_res.stdout[:500]}")
        sys.exit(1)

    # 5. Update version.json & git push
    print("[5/5] Updating version.json and pushing to GitHub...")
    version_info = {
        "latestVersionCode": version_code,
        "latestVersionName": version_name,
        "minSupportedVersionCode": version_code if is_force else 1,
        "isForceUpdate": is_force,
        "title": f"تحديث جديد متوفر v{version_name} 🚀",
        "releaseNotes": release_notes,
        "downloadUrl": download_url,
        "publishDate": datetime.now().strftime("%Y-%m-%d")
    }

    with open("version.json", "w", encoding="utf-8") as f:
        json.dump(version_info, f, ensure_ascii=False, indent=2)
    print("-> Updated version.json")

    subprocess.run(["git", "add", "."], check=True)
    commit_type = "Forced Update" if is_force else "Optional Update"
    subprocess.run(["git", "commit", "-m", f"Publish version v{version_name} (Code: {version_code}) - {commit_type}"], check=True)
    subprocess.run(["git", "push", "origin", "main"], check=True)

    print("==========================================")
    print(f"🎉 Successfully published v{version_name} ({commit_type}) to GitHub!")
    print(f"🔗 URL: {download_url}")
    print("==========================================")

if __name__ == "__main__":
    main()


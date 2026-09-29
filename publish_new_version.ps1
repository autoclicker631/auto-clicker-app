param (
    [Parameter(Mandatory=$false)]
    [string]$VersionName = "1.1.0",

    [Parameter(Mandatory=$false)]
    [int]$VersionCode = 2,

    [Parameter(Mandatory=$false)]
    [bool]$ForceUpdate = $false,

    [Parameter(Mandatory=$false)]
    [string]$ReleaseNotes = "• تحسينات عامة في الأداء والسرعة`n• إصلاح كافة المشاكل السابقة"
)

$tokenFile = Join-Path $PSScriptRoot "github_token.txt"
if (Test-Path $tokenFile) {
    $token = (Get-Content $tokenFile -Raw).Trim()
} elseif ($env:GITHUB_TOKEN) {
    $token = $env:GITHUB_TOKEN
} else {
    Write-Host "❌ Error: github_token.txt not found!" -ForegroundColor Red
    exit 1
}

$repo = "autoclicker631/auto-clicker-app"

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "🚀 Publishing New Auto Clicker Version: v$VersionName (Code: $VersionCode)" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan

# 1. Update version in app/build.gradle.kts
$gradleFile = "app/build.gradle.kts"
$gradleContent = Get-Content $gradleFile -Raw
$gradleContent = $gradleContent -replace 'versionCode\s*=\s*\d+', "versionCode = $VersionCode"
$gradleContent = $gradleContent -replace 'versionName\s*=\s*"[^"]+"', "versionName = `"$VersionName`""
Set-Content $gradleFile $gradleContent -NoNewline
Write-Host "✓ Updated app/build.gradle.kts" -ForegroundColor Green

# 2. Build Release APK
Write-Host "🔨 Building Release APK with Gradle..." -ForegroundColor Yellow
.\gradlew.bat assembleRelease
if ($LASTEXITCODE -ne 0) {
    Write-Host "❌ Build failed!" -ForegroundColor Red
    exit 1
}
Write-Host "✓ Release APK built successfully" -ForegroundColor Green

# 3. Create GitHub Release
$headers = @{
    "Authorization" = "token $token"
    "Accept" = "application/vnd.github.v3+json"
}

$releaseJson = @{
    tag_name = "v$VersionName"
    target_commitish = "main"
    name = "v$VersionName Release"
    body = $ReleaseNotes
    draft = $false
    prerelease = $false
} | ConvertTo-Json

$releaseBodyBytes = [System.Text.Encoding]::UTF8.GetBytes($releaseJson)

Write-Host "📦 Creating GitHub Release tag: v$VersionName..." -ForegroundColor Yellow
$releaseResponse = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases" -Method Post -Headers $headers -Body $releaseBodyBytes -ContentType "application/json; charset=utf-8"
$uploadUrl = $releaseResponse.upload_url -replace '\{.*\}', "?name=AutoClicker-v$VersionName.apk"

# 4. Upload APK asset
Write-Host "⬆️ Uploading AutoClicker-v$VersionName.apk to GitHub..." -ForegroundColor Yellow
$apkPath = "app/build/outputs/apk/release/app-release.apk"
$uploadHeaders = @{
    "Authorization" = "token $token"
    "Content-Type" = "application/vnd.android.package-archive"
}
$uploadResponse = Invoke-RestMethod -Uri $uploadUrl -Method Post -Headers $uploadHeaders -InFile $apkPath
$downloadUrl = $uploadResponse.browser_download_url
Write-Host "✓ Uploaded! Direct URL: $downloadUrl" -ForegroundColor Green

# 5. Update version.json
$versionJson = @{
    latestVersionCode = $VersionCode
    latestVersionName = $VersionName
    minSupportedVersionCode = if ($ForceUpdate) { $VersionCode } else { 1 }
    isForceUpdate = $ForceUpdate
    title = "تحديث جديد متوفر v$VersionName 🚀"
    releaseNotes = $ReleaseNotes
    downloadUrl = $downloadUrl
    publishDate = (Get-Date).ToString("yyyy-MM-dd")
} | ConvertTo-Json -Depth 4

[System.IO.File]::WriteAllText("version.json", $versionJson, [System.Text.Encoding]::UTF8)
Write-Host "✓ Updated version.json" -ForegroundColor Green

# 6. Git commit and push
git add .
git commit -m "Publish version v$VersionName (Code: $VersionCode)"
git push origin main
Write-Host "==========================================" -ForegroundColor Green
Write-Host "🎉 Version v$VersionName published successfully to all users!" -ForegroundColor Green
Write-Host "==========================================" -ForegroundColor Green

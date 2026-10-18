param(
    [Parameter(Mandatory=$true)][string]$SdkPath,
    [string]$ExpectedSigner='63c4d2868987dfc9be77e3321df5925a7051e585e49538e5bdfac0df45a5a9e5'
)
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$apk=Join-Path $projectRoot 'android\app\build\outputs\apk\release\app-release.apk'
$buildTools=Join-Path $SdkPath 'build-tools\35.0.0'
if(-not (Test-Path -LiteralPath $apk)){throw 'Build the signed release first.'}
$certOutput=& (Join-Path $buildTools 'apksigner.bat') verify --print-certs $apk
if($LASTEXITCODE -ne 0){throw 'APK signature verification failed.'}
$certText=$certOutput -join "`n"
if($certText -notmatch 'Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})'){throw 'Signer not found.'}
if($Matches[1] -ne $ExpectedSigner){throw 'Release signer differs from the expected installed identity.'}
$badging=(& (Join-Path $buildTools 'aapt.exe') dump badging $apk) -join "`n"
if($LASTEXITCODE -ne 0){throw 'APK metadata could not be read.'}
if($badging -match 'application-debuggable'){throw 'Debuggable APK must not be released.'}
if($badging -notmatch "package: name='com.ldj.djspdf' versionCode='4' versionName='1.2.1'"){throw 'Unexpected APK package or version.'}
$hash=(Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
$out=Join-Path $projectRoot 'RELEASES\1.2.1'
New-Item -ItemType Directory -Path $out -Force | Out-Null
$destination=Join-Path $out 'DJS-PDF-v1.2.1.apk'
if(Test-Path -LiteralPath $destination){
    if((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant() -ne $hash){throw 'A different candidate already exists; retain it and choose a distinct version before replacing it.'}
}else{Copy-Item -LiteralPath $apk -Destination $destination}
if((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant() -ne $hash){throw 'Candidate copy verification failed.'}
$metadata=[ordered]@{
    versionCode=4;versionName='1.2.1';published=$false
    apkUrl='https://github.com/ldjkorea/DJS-PDF/releases/download/v1.2.1/DJS-PDF-v1.2.1.apk'
    sha256=$hash;size=(Get-Item -LiteralPath $destination).Length;signerSha256=$ExpectedSigner
    releaseNotes='문서 식별·암호·실패 격리·검색·렌더링·모바일 배치·네이티브 공유·업데이트 검증 안정화'
}
$metadata | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $out 'version-candidate.json') -Encoding utf8
Write-Output "LOCAL CANDIDATE ONLY: $destination"
Write-Output "SHA256=$hash"
Write-Output 'Signature and non-debuggable flags verified. No upload, push, or publication performed.'

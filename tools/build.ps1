# Build Mcall APK: aapt2 + kotlinc + d8 + apksigner (no Gradle needed).
# Usage:  .\build.ps1                build + install to first connected device
#         .\build.ps1 -NoInstall     build only
#         .\build.ps1 -Serial XYZ    install to a specific device
param(
    [switch]$NoInstall,
    [string]$Serial = ""
)
$prevEA = $ErrorActionPreference; $ErrorActionPreference = "Continue"

$sdk    = "$env:LOCALAPPDATA\Android\Sdk"
$bt     = "$sdk\build-tools\36.1.0"
$plat   = "$sdk\platforms\android-36\android.jar"
$adb    = "$sdk\platform-tools\adb.exe"
$ktHome = "$env:LOCALAPPDATA\kotlin-compiler\kotlinc"
$ktc    = "$ktHome\bin\kotlinc.bat"
$stdlib = "$ktHome\lib\kotlin-stdlib.jar"

$proj   = (Resolve-Path "$PSScriptRoot\..").Path
$out    = "$proj\out"
$libs   = @(Get-ChildItem "$proj\libs" -Filter *.jar -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })

if (!(Test-Path $plat)) { throw "platform not found: $plat" }
if (!(Test-Path $ktc))  { throw "kotlinc not found: $ktc" }
Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path "$out\gen","$out\classes","$out\dex" | Out-Null

Write-Host "== aapt2 compile =="
& "$bt\aapt2.exe" compile --dir "$proj\res" -o "$out\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "== aapt2 link =="
& "$bt\aapt2.exe" link -o "$out\unsigned.apk" -I $plat --manifest "$proj\AndroidManifest.xml" `
  --java "$out\gen" --min-sdk-version 29 --target-sdk-version 36 `
  --version-code 3 --version-name "3.0" "$out\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "== kotlinc =="
$cp = @($plat) + $libs -join [IO.Path]::PathSeparator
$srcs = @(Get-ChildItem "$proj\src" -Recurse -Include *.kt,*.java | ForEach-Object { $_.FullName }) + `
        @(Get-ChildItem "$out\gen" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
& $ktc -cp $cp -d "$out\classes" -jvm-target 1.8 -nowarn $srcs > "$out\kotlinc.log" 2>&1
$kc = $LASTEXITCODE
Get-Content "$out\kotlinc.log" | Select-Object -First 40
if ($kc -ne 0) { throw "kotlinc failed" }

Write-Host "== d8 =="
$dexIn = @(Get-ChildItem "$out\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName }) + $libs + @($stdlib)
& "$bt\d8.bat" --min-api 29 --lib $plat --output "$out\dex" $dexIn
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "== add dex + align + sign =="
python -c @"
import zipfile
with zipfile.ZipFile(r'$out\unsigned.apk','a',zipfile.ZIP_DEFLATED) as z:
    z.write(r'$out\dex\classes.dex','classes.dex')
print('dex added')
"@
if ($LASTEXITCODE -ne 0) { throw "dex pack failed" }
& "$bt\zipalign.exe" -f 4 "$out\unsigned.apk" "$out\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

$ks = "$proj\tools\debug.keystore"
if (!(Test-Path $ks)) {
  & keytool -genkeypair -keystore $ks -alias androiddebugkey -keyalg RSA -keysize 2048 `
    -validity 10950 -storepass android -keypass android -dname "CN=Mcall Debug"
  if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android --out "$out\mcall.apk" "$out\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }
& "$bt\apksigner.bat" verify "$out\mcall.apk"
Write-Host "BUILD OK: $out\mcall.apk"

if (-not $NoInstall) {
    Write-Host "== install =="
    if ($Serial -eq "") {
        $Serial = (& $adb devices | Select-String -Pattern "\tdevice" | ForEach-Object { ($_ -split "\t")[0] } | Select-Object -First 1)
        if (-not $Serial) { Write-Host "no device connected; skipping install"; return }
    }
    & $adb -s $Serial install -r "$out\mcall.apk"
}
$ErrorActionPreference = $prevEA

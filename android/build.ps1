# Compila RetroVault.apk: biblioteca web (Flash + consolas) + PSP nativo (PPSSPP, GPL-2.0) + DS nativo (melonDS DS, GPL-3.0).
# 1a vez descarga Java, Android SDK/NDK y el código de PPSSPP (~5 GB) en C:\Users\Craft\android-tools
$ErrorActionPreference = "Stop"
$T    = "C:\Users\Craft\android-tools"
$A    = $PSScriptRoot                      # carpeta android/
$ROOT = Split-Path $A                      # carpeta de la web
$OUT  = "$A\build"
$PPSSPP_TAG = "v1.20.4"
$PP   = "$T\ppsspp"
function Run {
  $a = @($args | ForEach-Object { $_ })   # aplana listas de archivos en argumentos sueltos
  & $a[0] $a[1..($a.Count-1)]; if ($LASTEXITCODE) { throw "Falló: $($a[0])" }
}

# --- Herramientas (solo la primera vez) ---
New-Item -ItemType Directory -Force $T | Out-Null
if (-not (Test-Path "$T\jdk\bin\java.exe")) {
  Write-Host "Descargando Java..."
  curl.exe -L -s -o "$T\jdk.zip" "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse"
  Expand-Archive "$T\jdk.zip" "$T\jdk-tmp" -Force
  Move-Item (Get-ChildItem "$T\jdk-tmp")[0].FullName "$T\jdk"; Remove-Item "$T\jdk-tmp"
}
$env:JAVA_HOME = "$T\jdk"; $env:Path = "$T\jdk\bin;$env:Path"; $env:ANDROID_HOME = "$T\sdk"
$SDKM = "$T\sdk\cmdline-tools\latest\bin\sdkmanager.bat"
if (-not (Test-Path $SDKM)) {
  Write-Host "Descargando herramientas de Android..."
  curl.exe -L -s -o "$T\cmdtools.zip" "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
  Expand-Archive "$T\cmdtools.zip" "$T\sdk-tmp" -Force
  New-Item -ItemType Directory -Force "$T\sdk\cmdline-tools" | Out-Null
  Move-Item "$T\sdk-tmp\cmdline-tools" "$T\sdk\cmdline-tools\latest"; Remove-Item "$T\sdk-tmp"
}
$PKGS = "platforms;android-36", "build-tools;36.0.0", "ndk;29.0.14206865", "cmake;3.31.6"
if (-not (Test-Path "$T\sdk\ndk\29.0.14206865") -or -not (Test-Path "$T\sdk\platforms\android-36") -or -not (Test-Path "$T\sdk\cmake\3.31.6")) {
  Write-Host "Instalando Android SDK 36 + NDK + CMake (acepta licencias)..."
  Set-Content "$T\yes.txt" ("y`r`n" * 30) -Encoding ascii
  cmd /c "`"$SDKM`" --sdk_root=`"$T\sdk`" --licenses < `"$T\yes.txt`"" | Out-Null
  Run $SDKM --sdk_root="$T\sdk" $PKGS
}

# --- Código de PPSSPP + cambios de RetroVault (parche guardado en este proyecto) ---
if (-not (Test-Path "$PP\CMakeLists.txt")) {
  Write-Host "Descargando PPSSPP $PPSSPP_TAG..."
  Run git clone --depth 1 --branch $PPSSPP_TAG --recurse-submodules --shallow-submodules -j 8 https://github.com/hrydgard/ppsspp.git $PP
  Run git -C $PP apply "$A\ppsspp-retrovault.patch"
}

# --- Web de la app (dist\ = lo que va dentro del APK) ---
$DIST = "$ROOT\dist"; $NPM = "$OUT\npm"
Remove-Item -Recurse -Force $OUT, $DIST -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $DIST, $NPM | Out-Null
Copy-Item "$ROOT\index.html", "$ROOT\play.html", "$ROOT\db.js", "$ROOT\sw.js", "$ROOT\manifest.json", "$ROOT\icon.svg", "$ROOT\icon-192.png", "$ROOT\icon-512.png" $DIST
Copy-Item "$ROOT\fonts" "$DIST\fonts" -Recurse   # tipografía retro (Press Start 2P, OFL)

# Ruffle (Flash) y EmulatorJS (consolas) dentro de la app para no necesitar internet
$CORES = "fceumm", "snes9x", "mgba", "genesis_plus_gx", "smsplus",   # NES, SNES, GB/GBA, Genesis/Game Gear, Master System
         "pcsx_rearmed", "mupen64plus_next", "fbneo", "mednafen_pce",    # PS1, N64, Arcade, PC Engine
         "stella2014", "prosystem", "handy", "mednafen_ngp", "mednafen_wswan"  # Atari 2600/7800/Lynx, Neo Geo Pocket, WonderSwan
Write-Host "Descargando Ruffle y EmulatorJS..."
Push-Location $NPM
Run npm pack "@ruffle-rs/ruffle" "@emulatorjs/emulatorjs" ($CORES | ForEach-Object { "@emulatorjs/core-$_" }) --silent
Pop-Location
function Unpack($pattern) {
  $d = "$NPM\x" + [guid]::NewGuid(); New-Item -ItemType Directory $d | Out-Null
  Run tar -xzf (Get-ChildItem "$NPM\$pattern")[0].FullName -C $d
  "$d\package"
}
Copy-Item (Unpack "ruffle-rs-ruffle-*.tgz") "$DIST\ruffle" -Recurse
New-Item -ItemType Directory -Force "$DIST\emulatorjs" | Out-Null
Copy-Item ((Unpack "emulatorjs-emulatorjs-*.tgz") + "\data") "$DIST\emulatorjs\data" -Recurse
New-Item -ItemType Directory -Force "$DIST\emulatorjs\data\cores\reports" | Out-Null
foreach ($c in $CORES) {
  $p = Unpack "emulatorjs-core-$c-*.tgz"
  $files = @("$p\$c-wasm.data", "$p\$c-legacy-wasm.data") | Where-Object { Test-Path $_ }   # sin hilos: no hace falta COOP/COEP
  if (-not $files) { throw "El núcleo $c no trae versión sin hilos" }
  Copy-Item $files "$DIST\emulatorjs\data\cores"
  Copy-Item "$p\reports\$c.json" "$DIST\emulatorjs\data\cores\reports"
}
foreach ($must in "ruffle\ruffle.js", "emulatorjs\data\loader.js", "emulatorjs\data\src\emulator.js", "emulatorjs\data\cores\mgba-wasm.data") {
  if (-not (Test-Path "$DIST\$must")) { throw "Falta $must" }
}

# --- Meter RetroVault dentro del proyecto Android de PPSSPP ---
$PA = "$PP\android"
Remove-Item -Recurse -Force "$PA\rv-assets", "$PA\rv-res", "$PA\rv-jni", "$PA\rv-native", "$PA\src\com\jugarswf" -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$PA\rv-assets", "$PA\rv-res\mipmap-xxxhdpi", "$PA\src\com\jugarswf" | Out-Null
# Nintendo DS: reproductor libretro propio (C) + núcleo melonDS DS precompilado por libretro (GPL-3), en caché por arquitectura
Copy-Item "$A\native" "$PA\rv-native" -Recurse
foreach ($abi in "arm64-v8a", "armeabi-v7a") {
  $DSCORE = "$T\cores\$abi\melondsds_libretro_android.so"
  if (-not (Test-Path $DSCORE)) {
    Write-Host "Descargando núcleo de Nintendo DS (melonDS DS, $abi)..."
    New-Item -ItemType Directory -Force "$T\cores\$abi" | Out-Null
    curl.exe -s -L -o "$T\cores\$abi\melondsds.zip" "https://buildbot.libretro.com/nightly/android/latest/$abi/melondsds_libretro_android.so.zip"
    Expand-Archive "$T\cores\$abi\melondsds.zip" "$T\cores\$abi" -Force
    if (-not (Test-Path $DSCORE)) { throw "No se pudo descargar el núcleo de DS ($abi)" }
  }
  New-Item -ItemType Directory -Force "$PA\rv-jni\$abi" | Out-Null
  Copy-Item $DSCORE "$PA\rv-jni\$abi\libmelondsds_libretro.so"   # Android solo empaqueta bibliotecas que empiezan por lib
}
Copy-Item $DIST "$PA\rv-assets\www" -Recurse
Copy-Item "$ROOT\icon-192.png" "$PA\rv-res\mipmap-xxxhdpi\rv_icon.png"
Copy-Item "$A\src\com\jugarswf\*.java" "$PA\src\com\jugarswf"
Set-Content "$PP\local.properties" ("sdk.dir=" + "$T\sdk".Replace('\', '\\') + "`ncmake.dir=" + "$T\sdk\cmake\3.31.6".Replace('\', '\\')) -Encoding ascii

# --- Compilar (la primera vez tarda bastante: compila todo PPSSPP en C++) ---
# Misma firma siempre, para poder actualizar la app sin desinstalarla
$KS = "$A\jugarswf.keystore"
if (-not (Test-Path $KS)) {
  Run keytool -genkeypair -keystore $KS -alias jugarswf -keyalg RSA -keysize 2048 -validity 10000 -storepass jugarswf -keypass jugarswf -dname "CN=Jugar SWF"
}
Write-Host "Compilando RetroVault + PPSSPP..."
Push-Location $PP
Run .\gradlew.bat --no-daemon ":android:assembleNormalRelease" "-PRELEASE_STORE_FILE=$KS" "-PRELEASE_STORE_PASSWORD=jugarswf" "-PRELEASE_KEY_ALIAS=jugarswf" "-PRELEASE_KEY_PASSWORD=jugarswf" "-PANDROID_VERSION_CODE=14" "-PANDROID_VERSION_NAME=3.11"
Pop-Location
$apk = Get-ChildItem "$PA\build\outputs\apk\normal\release\*.apk" | Sort-Object LastWriteTime | Select-Object -Last 1
if (-not $apk) { throw "No se generó el APK" }
Copy-Item $apk.FullName "$ROOT\RetroVault.apk" -Force
Run "$T\sdk\build-tools\36.0.0\apksigner.bat" verify "$ROOT\RetroVault.apk"
Write-Host "`nLISTO: $ROOT\RetroVault.apk" -ForegroundColor Green

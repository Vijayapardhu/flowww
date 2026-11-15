<#
.SYNOPSIS
  Cross-compiles the Flowww Rust core for Android and places the .so files
  where Gradle expects them.

.DESCRIPTION
  Builds `flowww-ffi` as a cdylib for each supported Android ABI using the
  pinned NDK's clang wrappers (no cargo-ndk required), then copies the
  resulting libraries into `app/src/main/jniLibs/<abi>/`.

  Binaries are NEVER committed: `app/src/main/jniLibs/` is gitignored.
  Generated Kotlin bindings ARE committed under
  `app/src/main/java/com/flowww/android/uniffi/`.

.PARAMETER Configuration
  Rust profile to build: `Release` (default) or `Debug`.

.PARAMETER Abi
  ABIs to build. Default: both supported ABIs (arm64-v8a, x86_64).

.PARAMETER GenerateKotlinBindings
  If set, runs `uniffi-bindgen generate` against the built cdylib and writes
  Kotlin bindings into the app source tree. Requires the `uniffi` CLI
  (`cargo install uniffi --version 0.32.2 --features cli`).

.PARAMETER KeepSymbols
  Keep the symbol table in the staged jniLibs .so files instead of stripping
  them with llvm-strip. The `target/` build keeps symbols regardless; they are
  what `uniffi-bindgen` reads. Stripping only shrinks the staged copies that
  Gradle packages.

.PARAMETER NdkVersion
  NDK version to use. Defaults to the pinned 27.1.12297006.

.EXAMPLE
  .\scripts\build_rust_android.ps1
  .\scripts\build_rust_android.ps1 -Configuration Debug -Abi arm64-v8a
  .\scripts\build_rust_android.ps1 -GenerateKotlinBindings
#>

[CmdletBinding()]
param(
    [ValidateSet('Release', 'Debug')]
    [string]$Configuration = 'Release',

    [ValidateSet('arm64-v8a', 'x86_64')]
    [string[]]$Abi = @('arm64-v8a', 'x86_64'),

    [switch]$GenerateKotlinBindings,

    [switch]$KeepSymbols,

    [string]$NdkVersion = '27.1.12297006'
)

$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------
$ScriptDir = $PSScriptRoot
$AndroidRoot = Split-Path $ScriptDir -Parent
$RepoRoot = Split-Path (Split-Path $AndroidRoot -Parent) -Parent

function Resolve-SdkDir {
    if ($env:ANDROID_HOME) { return $env:ANDROID_HOME }
    if ($env:ANDROID_SDK_ROOT) { return $env:ANDROID_SDK_ROOT }
    $props = Join-Path $AndroidRoot 'local.properties'
    if (Test-Path $props) {
        foreach ($line in Get-Content $props) {
            if ($line -match '^\s*sdk\.dir\s*=\s*(.+?)\s*$') { return $Matches[1] }
        }
    }
    $fallback = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    if (Test-Path $fallback) { return $fallback }
    throw 'Android SDK not found: set ANDROID_HOME, sdk.dir in local.properties, or install the SDK.'
}

$SdkDir = Resolve-SdkDir
$NdkDir = Join-Path $SdkDir "ndk\$NdkVersion"
if (-not (Test-Path $NdkDir)) {
    throw "NDK $NdkVersion not found at $NdkDir. Installed versions: " +
        ((Get-ChildItem (Join-Path $SdkDir 'ndk') -ErrorAction SilentlyContinue |
            ForEach-Object Name) -join ', ')
}

if ($IsWindows -or $null -eq $IsWindows) {
    $HostTag = 'windows-x86_64'
} elseif ($IsLinux) {
    $HostTag = 'linux-x86_64'
} else {
    $HostTag = 'darwin-x86_64'
}
$ToolchainBin = Join-Path $NdkDir "toolchains\llvm\prebuilt\$HostTag\bin"
if (-not (Test-Path $ToolchainBin)) {
    throw "NDK toolchain bin not found: $ToolchainBin"
}

$JniLibs = Join-Path $AndroidRoot 'app\src\main\jniLibs'

# ---------------------------------------------------------------------------
# Rust prerequisites
# ---------------------------------------------------------------------------
if (-not (Get-Command cargo -ErrorAction SilentlyContinue)) {
    throw 'cargo is not on PATH. Install Rust via rustup first.'
}

# Triple -> Android ABI
$Targets = @{
    'arm64-v8a'  = 'aarch64-linux-android'
    'x86_64'     = 'x86_64-linux-android'
}

# Install any missing rustup targets (idempotent).
foreach ($androidAbi in $Abi) {
    $triple = $Targets[$androidAbi]
    $installed = rustup target list --installed 2>$null
    if (-not ($installed -contains $triple)) {
        Write-Host "Installing rustup target $triple ..."
        rustup target add $triple
        if ($LASTEXITCODE -ne 0) { throw "rustup target add $triple failed" }
    }
}

# ---------------------------------------------------------------------------
# Build + stage
# ---------------------------------------------------------------------------
$ApiLevel = 26   # matches minSdk 26
# 'Release' maps to release-android: identical to 'release' but with strip=false,
# required because uniffi-bindgen reads UNIFFI_META_* symbols out of the cdylib.
$CargoProfile = if ($Configuration -eq 'Release') { 'release-android' } else { 'debug' }

# Clean stale libraries so a failed build never leaves a partial set.
if (Test-Path $JniLibs) {
    Remove-Item $JniLibs -Recurse -Force
}

$Built = @()
foreach ($androidAbi in $Abi) {
    $triple = $Targets[$androidAbi]
    $clang = Join-Path $ToolchainBin "$triple${ApiLevel}-clang.cmd"
    if (-not (Test-Path $clang)) {
        # Non-Windows hosts ship the plain clang executable.
        $clang = Join-Path $ToolchainBin "$triple${ApiLevel}-clang"
    }
    $llvmAr = Join-Path $ToolchainBin 'llvm-ar.exe'
    if (-not (Test-Path $llvmAr)) { $llvmAr = Join-Path $ToolchainBin 'llvm-ar' }
    if (-not (Test-Path $clang)) { throw "clang wrapper not found: $clang" }
    if (-not (Test-Path $llvmAr)) { throw "llvm-ar not found: $llvmAr" }

    $envTripleUpper = ($triple -replace '-', '_').ToUpperInvariant()
    $env:CC = $clang
    $env:AR = $llvmAr
    Set-Item "env:CC_$triple" $clang
    Set-Item "env:CC_$envTripleUpper" $clang
    Set-Item "env:AR_$triple" $llvmAr
    Set-Item "env:AR_$envTripleUpper" $llvmAr
    Set-Item "env:CARGO_TARGET_${envTripleUpper}_LINKER" $clang
    Set-Item "env:CARGO_TARGET_${envTripleUpper}_AR" $llvmAr

    Write-Host "=== cargo build --$CargoProfile --target $triple ===" -ForegroundColor Cyan
    cargo build --profile $CargoProfile --target $triple -p flowww-ffi
    if ($LASTEXITCODE -ne 0) { throw "cargo build failed for $triple" }

    $so = Join-Path $RepoRoot "target\$triple\$CargoProfile\libflowww_ffi.so"
    if (-not (Test-Path $so)) { throw "expected output not found: $so" }

    $destDir = Join-Path $JniLibs $androidAbi
    New-Item -ItemType Directory -Force -Path $destDir | Out-Null
    $stagedSo = Join-Path $destDir 'libflowww_ffi.so'
    Copy-Item $so $stagedSo -Force

    # The APK does not need the metadata-carrying symbol table (that is what
    # target/ keeps for uniffi-bindgen). Strip the staged copy to halve the
    # packaged size. -KeepSymbols skips this for debugging.
    if (-not $KeepSymbols) {
        $llvmStrip = Join-Path $ToolchainBin 'llvm-strip.exe'
        if (-not (Test-Path $llvmStrip)) { $llvmStrip = Join-Path $ToolchainBin 'llvm-strip' }
        if (Test-Path $llvmStrip) {
            & $llvmStrip $stagedSo
            if ($LASTEXITCODE -ne 0) { throw "llvm-strip failed for $stagedSo" }
        } else {
            Write-Warning "llvm-strip not found; staged $androidAbi .so keeps its symbol table ($so)"
        }
    }

    $size = [math]::Round((Get-Item $stagedSo).Length / 1MB, 2)
    $Built += [pscustomobject]@{ Abi = $androidAbi; Triple = $triple; SizeMb = $size }
    Write-Host "  -> app/src/main/jniLibs/$androidAbi/libflowww_ffi.so ($size MB)"
}

# ---------------------------------------------------------------------------
# Optional Kotlin binding generation
# ---------------------------------------------------------------------------
if ($GenerateKotlinBindings) {
    $bindgen = Get-Command uniffi-bindgen -ErrorAction SilentlyContinue
    if (-not $bindgen) {
        throw 'uniffi-bindgen not on PATH. Install with: cargo install uniffi --version 0.32.2 --features cli'
    }
    # uniffi-bindgen appends the package directory tree to the out-dir, so
    # the out-dir must be the source root, not the package directory.
    $firstAbi = $Abi[0]
    $so = Join-Path $RepoRoot "target\$($Targets[$firstAbi])\$CargoProfile\libflowww_ffi.so"
    $outDir = Join-Path $AndroidRoot 'app\src\main\java'
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null
    Write-Host "=== uniffi-bindgen generate --library $so --language kotlin ===" -ForegroundColor Cyan
    & $bindgen.Source generate --library $so --language kotlin --out-dir $outDir
    if ($LASTEXITCODE -ne 0) { throw 'uniffi-bindgen failed' }
    $bindingFile = Join-Path $outDir 'com\flowww\android\uniffi\flowww_ffi.kt'
    Write-Host "Kotlin bindings written to $bindingFile (commit these; they are source)."
}

Write-Host ''
Write-Host 'Built and staged:' -ForegroundColor Green
$Built | Format-Table Abi, Triple, SizeMb -AutoSize | Out-String | Write-Host
Write-Host 'Next: assembleDebug will package these .so files automatically.'

<#
.SYNOPSIS
  Idempotent first-run installer for the EspeonAnim processor (Windows PowerShell 5.1, no admin needed).

.DESCRIPTION
  Creates processor\.venv (Python 3.12 via uv), installs torch, clones GEM-X at a pinned commit, installs the
  Python packages, downloads the model weights / SOMA assets / MediaPipe pose model into processor\models
  (files already present with the expected size are skipped) and finally runs `python -m espeon_processor doctor`.

  Machine-readable progress lines on stdout:   ESPEON_SETUP {"step":"...","progress":0.35,"message":"..."}
  Exit codes: 0 ok, 1 unexpected error, 2 licenses not accepted, 3 git missing, 4 uv unavailable,
              5 venv / package install failed, 6 download failed, 7 doctor reported problems.

.PARAMETER ProcessorDir          Processor folder (default: parent of this script's folder).
.PARAMETER Python                Python version for the venv (default 3.12).
.PARAMETER Cuda                  auto (default: cu128 if nvidia-smi finds an NVIDIA GPU, else cpu), cu128 or cpu.
.PARAMETER AcceptModelLicenses   Required for anything that is not -DryRun: confirms the model licenses printed below.
.PARAMETER DryRun                Print what would be done without changing anything.
#>
[CmdletBinding()]
param(
    [string]$ProcessorDir = '',
    [string]$Python = '3.12',
    [ValidateSet('auto', 'cu128', 'cpu')][string]$Cuda = 'auto',
    [switch]$AcceptModelLicenses,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
try { [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12 } catch {}

$GemxRepo   = 'https://github.com/NVlabs/gem-x'
$GemxCommit = '4aef1452e5df171deff4f293c08e643eb450981e'
$SomaRev    = 'v0.3.3'
$MediaPipeUrl = 'https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_heavy/float16/latest/pose_landmarker_heavy.task'

# name, size in bytes (for skip-if-present), destination below models\
$GemxFiles = @(
    @('body_s240000.ckpt', 3538753341), @('body_config.json', 1086), @('vitpose.pth', 3388483384),
    @('motion_native77_e774_s292175.ckpt', 187134331), @('motion_stats_raw.npz', 21320),
    @('motion_native77.yaml', 823), @('body_video_native77.yaml', 1286))
$SomaFiles = @(
    @('SOMA_neutral.npz', 26608121), @('SOMA_template_rig.usda', 28306038), @('SOMA_procedural_transforms.json', 20045))
$MediaPipeSize = 30664242

function Emit([string]$step, [double]$progress, [string]$message) {
    $o = [ordered]@{ step = $step; progress = [math]::Round($progress, 3); message = $message }
    Write-Host ('ESPEON_SETUP ' + ($o | ConvertTo-Json -Compress))
}
function Info([string]$m) { Write-Host $m }
function Die([int]$code, [string]$step, [string]$message) {
    Emit $step 1 ('ERROR: ' + $message)
    Write-Host ('ERROR: ' + $message)
    exit $code
}
function Plan([string]$m) { Write-Host ('[dry-run] ' + $m) }

# Runs a native program, echoing its output; returns the exit code.
function Invoke-Native([string]$exe, [string[]]$arguments, [string]$cwd = '') {
    $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    if ($cwd) { Push-Location $cwd }
    try {
        & $exe @arguments 2>&1 | ForEach-Object { Write-Host ("$_") }
        return $LASTEXITCODE
    } finally {
        if ($cwd) { Pop-Location }
        $ErrorActionPreference = $old
    }
}
# Runs a native program and returns its stdout as text (stderr dropped); $null on failure.
function Capture-Native([string]$exe, [string[]]$arguments, [string]$cwd = '') {
    $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    if ($cwd) { Push-Location $cwd }
    try {
        $out = & $exe @arguments 2>$null
        if ($LASTEXITCODE -ne 0) { return $null }
        return ($out -join "`n")
    } catch { return $null } finally {
        if ($cwd) { Pop-Location }
        $ErrorActionPreference = $old
    }
}
function FileHasSize([string]$path, [long]$size) {
    return ((Test-Path -LiteralPath $path -PathType Leaf) -and ((Get-Item -LiteralPath $path).Length -eq $size))
}

# ---------------------------------------------------------------- setup
if (-not $ProcessorDir) { $ProcessorDir = Split-Path -Parent $PSScriptRoot }
$ProcessorDir = [IO.Path]::GetFullPath($ProcessorDir)
$Venv = Join-Path $ProcessorDir '.venv'
$VenvPy = Join-Path $Venv 'Scripts\python.exe'
$ModelsDir = Join-Path $ProcessorDir 'models'
$GemxDir = Join-Path $ProcessorDir 'third_party\gem-x'

Info "EspeonAnim processor setup"
Info "  processor dir : $ProcessorDir"
Info "  dry run       : $($DryRun.IsPresent)"

Info ""
Info "Model licenses (downloaded by this script):"
Info "  - GEM-X weights (nvidia/GEM-X, ~7.1 GB): NVIDIA Open Model License (https://huggingface.co/nvidia/GEM-X). Its backbone embeds DINOv3 weights."
Info "  - SOMA-X assets (nvidia/SOMA-X @ $SomaRev, ~55 MB): Apache-2.0."
Info "  - MediaPipe pose_landmarker_heavy.task (~31 MB): Apache-2.0 (Google)."
Info "  - GEM-X source code (NVlabs/gem-x): Apache-2.0."
Info ""

if (-not (Test-Path -LiteralPath (Join-Path $ProcessorDir 'pyproject.toml'))) {
    Die 1 'init' "ProcessorDir '$ProcessorDir' does not contain pyproject.toml"
}
if (-not $DryRun -and -not $AcceptModelLicenses) {
    Die 2 'licenses' 'The model licenses listed above must be accepted: re-run with -AcceptModelLicenses.'
}

Emit 'init' 0.0 'Checking prerequisites'

# --- git
$git = Get-Command git.exe -ErrorAction SilentlyContinue
if (-not $git) {
    if ($DryRun) { Plan 'git not found: a real run would stop here (install Git for Windows from https://git-scm.com/download/win)' }
    else { Die 3 'prereq' 'git is required but was not found on PATH. Install Git for Windows (https://git-scm.com/download/win) and run the setup again.' }
} else { Info "git: $($git.Source)" }

# --- CUDA flavour
$cudaTag = $Cuda
if ($Cuda -eq 'auto') {
    $cudaTag = 'cpu'
    $smi = Get-Command nvidia-smi.exe -ErrorAction SilentlyContinue
    if ($smi) {
        $name = Capture-Native $smi.Source @('--query-gpu=name', '--format=csv,noheader')
        if ($name) { $cudaTag = 'cu128'; Info "NVIDIA GPU detected: $(($name -split "`n")[0].Trim())" }
    }
    if ($cudaTag -eq 'cpu') { Info 'No NVIDIA GPU detected: installing the CPU build of torch (processing will be slow).' }
}
$torchIndex = "https://download.pytorch.org/whl/$cudaTag"
Info "torch flavour: $cudaTag"

# --- uv
function Find-Uv {
    $c = Get-Command uv.exe -ErrorAction SilentlyContinue
    if ($c) { return $c.Source }
    $own = Join-Path $env:LOCALAPPDATA 'EspeonAnim\uv\uv.exe'
    if (Test-Path -LiteralPath $own) { return $own }
    $wg = Join-Path $env:LOCALAPPDATA 'Microsoft\WinGet\Packages'
    if (Test-Path -LiteralPath $wg) {
        $hit = Get-ChildItem -LiteralPath $wg -Directory -Filter 'astral-sh.uv_*' -ErrorAction SilentlyContinue |
            ForEach-Object { Join-Path $_.FullName 'uv.exe' } | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
        if ($hit) { return $hit }
    }
    $loc = Join-Path $env:USERPROFILE '.local\bin\uv.exe'
    if (Test-Path -LiteralPath $loc) { return $loc }
    return $null
}
$uv = Find-Uv
if ($uv) { Info "uv: $uv" }
elseif ($DryRun) { Plan "download uv with the official installer (https://astral.sh/uv/install.ps1) into $env:LOCALAPPDATA\EspeonAnim\uv" }
else {
    Emit 'uv' 0.03 'Installing uv (Python package manager)'
    $dir = Join-Path $env:LOCALAPPDATA 'EspeonAnim\uv'
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $env:UV_INSTALL_DIR = $dir
    $env:UV_NO_MODIFY_PATH = '1'
    try { Invoke-Expression ((New-Object Net.WebClient).DownloadString('https://astral.sh/uv/install.ps1')) | Out-Host }
    catch { Die 4 'uv' "Could not install uv: $($_.Exception.Message)" }
    $uv = Find-Uv
    if (-not $uv) { Die 4 'uv' 'uv was installed but uv.exe could not be located.' }
    Info "uv: $uv"
}

# ---------------------------------------------------------------- venv
Emit 'venv' 0.08 'Creating the Python environment'
if (Test-Path -LiteralPath $VenvPy) { Info "venv: present ($VenvPy)" }
elseif ($DryRun) { Plan "uv venv `"$Venv`" --python $Python (uv downloads Python $Python if needed)" }
else {
    $rc = Invoke-Native $uv @('venv', $Venv, '--python', $Python)
    if ($rc -ne 0 -or -not (Test-Path -LiteralPath $VenvPy)) { Die 5 'venv' "uv venv failed (exit $rc)." }
}

# ---------------------------------------------------------------- torch
Emit 'torch' 0.15 "Installing torch ($cudaTag)"
$torchOk = $false
if (Test-Path -LiteralPath $VenvPy) {
    $tv = Capture-Native $VenvPy @('-c', 'import torch; print(torch.__version__)')
    if ($tv) {
        $tv = $tv.Trim()
        if ($cudaTag -eq 'cpu') { $torchOk = $true } elseif ($tv -like "*+$cudaTag") { $torchOk = $true }
        Info "torch: $tv ($(if ($torchOk) { 'ok, skipping' } else { 'wrong build, reinstalling' }))"
    }
}
if (-not $torchOk) {
    if ($DryRun) { Plan "uv pip install torch torchvision --index-url $torchIndex" }
    else {
        $rc = Invoke-Native $uv @('pip', 'install', '--python', $VenvPy, 'torch', 'torchvision', '--index-url', $torchIndex)
        if ($rc -ne 0) { Die 5 'torch' "torch install failed (exit $rc)." }
    }
}

# ---------------------------------------------------------------- gem-x
Emit 'gemx' 0.35 'Fetching GEM-X source'
$head = $null
if (Test-Path -LiteralPath (Join-Path $GemxDir '.git')) { $head = Capture-Native 'git.exe' @('rev-parse', 'HEAD') $GemxDir; if ($head) { $head = $head.Trim() } }
if ($head -eq $GemxCommit) { Info "gem-x: at pinned commit $GemxCommit" }
elseif (-not $git) { Plan 'git unavailable; skipping gem-x plan' }
elseif ($DryRun) { Plan "fetch $GemxRepo at commit $GemxCommit into $GemxDir (current: $(if ($head) { $head } else { 'absent' }))" }
else {
    if ((Test-Path -LiteralPath $GemxDir) -and -not (Test-Path -LiteralPath (Join-Path $GemxDir '.git'))) {
        if (Get-ChildItem -LiteralPath $GemxDir -Force | Select-Object -First 1) {
            Die 5 'gemx' "$GemxDir exists but is not a git checkout. Delete it and run the setup again."
        }
    }
    New-Item -ItemType Directory -Force -Path $GemxDir | Out-Null
    if (-not (Test-Path -LiteralPath (Join-Path $GemxDir '.git'))) {
        if ((Invoke-Native 'git.exe' @('init', '-q') $GemxDir) -ne 0) { Die 5 'gemx' 'git init failed.' }
        if ((Invoke-Native 'git.exe' @('remote', 'add', 'origin', $GemxRepo) $GemxDir) -ne 0) { Die 5 'gemx' 'git remote add failed.' }
    }
    if ((Invoke-Native 'git.exe' @('fetch', '--depth', '1', 'origin', $GemxCommit) $GemxDir) -ne 0) { Die 5 'gemx' 'git fetch of the pinned GEM-X commit failed (network?).' }
    if ((Invoke-Native 'git.exe' @('-c', 'advice.detachedHead=false', 'checkout', '-q', 'FETCH_HEAD') $GemxDir) -ne 0) { Die 5 'gemx' 'git checkout of the pinned GEM-X commit failed.' }
}

# ---------------------------------------------------------------- packages
Emit 'packages' 0.45 'Installing Python packages'
if ($DryRun) {
    Plan "uv pip install -e `"third_party/gem-x[video]`" --extra-index-url $torchIndex   (no-op when satisfied)"
    Plan 'uv pip install -e ".[test]" huggingface_hub   (no-op when satisfied)'
} else {
    $rc = Invoke-Native $uv @('pip', 'install', '--python', $VenvPy, '-e', "$GemxDir[video]", '--extra-index-url', $torchIndex)
    if ($rc -ne 0) { Die 5 'packages' "Installing GEM-X failed (exit $rc)." }
    Emit 'packages' 0.55 'Installing the processor package'
    $rc = Invoke-Native $uv @('pip', 'install', '--python', $VenvPy, '-e', "$ProcessorDir[test]", 'huggingface_hub') $ProcessorDir
    if ($rc -ne 0) { Die 5 'packages' "Installing the processor package failed (exit $rc)." }
}

# ---------------------------------------------------------------- weights
$env:HF_HUB_DISABLE_SYMLINKS_WARNING = '1'
function Get-Hf {
    $hf = Join-Path $Venv 'Scripts\hf.exe'
    if (Test-Path -LiteralPath $hf) { return $hf }
    return $null
}
function Download-HfFiles([string]$repo, [object[]]$files, [string]$subdir, [string]$revision, [string]$step, [double]$progress, [string]$label) {
    $dest = Join-Path $ModelsDir $subdir
    $missing = @($files | Where-Object { -not (FileHasSize (Join-Path $dest $_[0]) $_[1]) })
    $total = ($files | ForEach-Object { $_[1] } | Measure-Object -Sum).Sum
    if ($missing.Count -eq 0) { Info "${label}: all $($files.Count) files present, skipping"; return }
    $names = @($missing | ForEach-Object { $_[0] })
    if ($DryRun) { Plan "hf download $repo $($names -join ' ')$(if ($revision) { " --revision $revision" }) --local-dir models\$subdir"; return }
    Emit $step $progress "Downloading $label ($($names.Count) file(s))"
    $hf = Get-Hf
    if (-not $hf) { Die 5 $step 'hf.exe not found in the venv (huggingface_hub missing).' }
    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    $a = @('download', $repo) + $names
    if ($revision) { $a += @('--revision', $revision) }
    $a += @('--local-dir', $dest)
    $rc = Invoke-Native $hf $a
    if ($rc -ne 0) { Die 6 $step "Downloading $label failed (exit $rc). Check your network connection and run the setup again; finished files are kept." }
    foreach ($f in $files) {
        if (-not (FileHasSize (Join-Path $dest $f[0]) $f[1])) {
            Die 6 $step "$($f[0]) has an unexpected size after download. Delete it from $dest and run the setup again."
        }
    }
}
Download-HfFiles 'nvidia/GEM-X'  $GemxFiles 'gem-x' ''        'weights_gemx' 0.60 'GEM-X weights'
Download-HfFiles 'nvidia/SOMA-X' $SomaFiles 'soma'  $SomaRev  'weights_soma' 0.85 'SOMA-X assets'

Emit 'weights_mediapipe' 0.92 'MediaPipe pose model'
$mpPath = Join-Path $ModelsDir 'pose_landmarker_heavy.task'
if (FileHasSize $mpPath $MediaPipeSize) { Info 'MediaPipe pose model: present, skipping' }
elseif ($DryRun) { Plan "download $MediaPipeUrl -> models\pose_landmarker_heavy.task" }
else {
    New-Item -ItemType Directory -Force -Path $ModelsDir | Out-Null
    $part = $mpPath + '.part'
    try {
        Invoke-WebRequest -Uri $MediaPipeUrl -OutFile $part -UseBasicParsing
        if (-not (FileHasSize $part $MediaPipeSize)) { throw 'unexpected file size' }
        Move-Item -LiteralPath $part -Destination $mpPath -Force
    } catch {
        Remove-Item -LiteralPath $part -Force -ErrorAction SilentlyContinue
        Die 6 'weights_mediapipe' "Downloading the MediaPipe pose model failed: $($_.Exception.Message)"
    }
}

# ---------------------------------------------------------------- ffmpeg
$ff = Get-Command ffmpeg.exe -ErrorAction SilentlyContinue
if ($ff) { Info "ffmpeg: $($ff.Source)" }
elseif ($DryRun) { Plan 'ffmpeg not on PATH: would try `winget install Gyan.FFmpeg`' }
else {
    Emit 'ffmpeg' 0.95 'Installing ffmpeg'
    $wg = Get-Command winget.exe -ErrorAction SilentlyContinue
    if ($wg) {
        Invoke-Native $wg.Source @('install', '--id', 'Gyan.FFmpeg', '-e', '--accept-package-agreements', '--accept-source-agreements', '--silent') | Out-Null
        $env:Path = [Environment]::GetEnvironmentVariable('Path', 'Machine') + ';' + [Environment]::GetEnvironmentVariable('Path', 'User')
    }
    if (-not (Get-Command ffmpeg.exe -ErrorAction SilentlyContinue)) {
        Info 'WARNING: ffmpeg/ffprobe not found on PATH. Install them (winget install Gyan.FFmpeg) and restart Unreal Editor.'
    }
}

# ---------------------------------------------------------------- doctor
Emit 'doctor' 0.97 'Checking the installation'
if ($DryRun) { Plan 'python -m espeon_processor doctor'; Emit 'done' 1.0 'Dry run finished, nothing was changed'; exit 0 }
$json = Capture-Native $VenvPy @('-m', 'espeon_processor', 'doctor') $ProcessorDir
if (-not $json) { Die 7 'doctor' 'The doctor command failed to run.' }
Info $json
try { $doc = $json | ConvertFrom-Json } catch { Die 7 'doctor' 'The doctor output was not valid JSON.' }
if (-not $doc.ok) { Die 7 'doctor' ('Doctor reported problems: ' + (($doc.problems) -join '; ')) }
$gpu = if ($doc.cuda_available) { "GPU: $($doc.gpu)" } else { 'CPU only' }
Emit 'done' 1.0 "Processor ready ($gpu)"
exit 0

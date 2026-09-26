<#
.SYNOPSIS
  Starts ArduPilot SITL (Copter or Plane) on Windows plus MAVProxy as the pilot's RC, for GCS checks.

.DESCRIPTION
  Uses Mission Planner's Windows SITL builds in Documents\Mission Planner\sitl. The Copter binary ships with
  Mission Planner; for Plane, run Plane SITL once from Mission Planner (Simulation tab), which downloads
  ArduPlane.exe, or download ArduPlane.elf from https://firmware.ardupilot.org/Tools/MissionPlanner/sitl/
  and save it there as ArduPlane.exe (plane.parm: ardupilot Tools/autotest/models/plane.parm).

  Ports (SITL instance 0):
    5760  SERIAL0  MAVProxy holds it. The Windows SITL build exits when this client disconnects, so keep MAVProxy open.
    5762  SERIAL1  free for a GCS over TCP: desktop 127.0.0.1:5762, Android emulator 10.0.2.2:5762.
    5763  SERIAL2  free for tools/sitl/sitl_pilot.py.
    UDP 14550      MAVProxy forwards here: the GCS's "SITL (UDP 14550)" profile. For the emulator, run
                   `adb emu redir add udp:14550:14550` with the desktop GCS closed (only one can listen).

  Survey camera (Pass 16): camera.parm is loaded too. SITL keeps parameters in eeprom.bin, and a saved value wins
  over the defaults files, so run with -Wipe once per vehicle (or whenever the defaults change) to start fresh.

  The pilot (you) types into the MAVProxy window, e.g.
    Copter: mode guided -> arm throttle -> takeoff 20 -> mode auto
    Plane:  mode guided -> arm throttle -> takeoff 20 -> mode auto   (needs ArduPlane 4.5+ for GUIDED takeoff;
            on older firmware use: mode takeoff, then mode auto once it's climbing)

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\sitl\start-sitl.ps1 -Vehicle plane
#>
param(
    [ValidateSet("copter", "plane")] [string]$Vehicle = "copter",
    # CMAC, the ArduPilot test field: lat, lon, alt (m AMSL), heading. Planes take off along the heading, down the runway.
    [string]$HomeLocation = "-35.363261,149.165230,584,353",
    [string]$SitlDir = (Join-Path ([Environment]::GetFolderPath("MyDocuments")) "Mission Planner\sitl"),
    # Default parameters. Mission Planner downloads plane.parm next to copter.parm the first time it runs Plane SITL;
    # an ArduPilot checkout has the same file in Tools\autotest\default_params.
    [string]$Defaults = "",
    # Start from factory parameters: SITL's --wipe clears this vehicle's saved eeprom.bin, so every value in the
    # defaults files (camera.parm included) applies. Without it, a value saved earlier wins over the defaults files.
    [switch]$Wipe
)

$exe = Join-Path $SitlDir $(if ($Vehicle -eq "plane") { "ArduPlane.exe" } else { "ArduCopter.exe" })
if (-not (Test-Path $exe)) { throw "$exe not found. For Plane, start Plane SITL once from Mission Planner so it downloads it." }
if (-not $Defaults) { $Defaults = Join-Path $SitlDir "default_params\$Vehicle.parm" }
if (-not (Test-Path $Defaults)) { throw "Default parameters not found: $Defaults (pass -Defaults <path to $Vehicle.parm>)" }
# The survey camera (Pass 16): SITL reads a comma-separated list, later files winning.
$Defaults = "$Defaults," + (Join-Path $PSScriptRoot "camera.parm")
$model = if ($Vehicle -eq "plane") { "plane" } else { "quad" }

# SITL keeps its parameters in eeprom.bin in the working folder. One folder per vehicle, so Plane never boots with
# Copter's saved parameters (or the other way round). The Cygwin DLLs are found next to the exe.
$work = Join-Path $SitlDir $Vehicle
New-Item -ItemType Directory -Force $work | Out-Null

$sitlArgs = @("--model", $model, "--home", $HomeLocation, "--defaults", "`"$Defaults`"", "-I0")
if ($Wipe) { $sitlArgs += "--wipe" }
Write-Host "Starting $Vehicle SITL at $HomeLocation$(if ($Wipe) { ' with fresh parameters (--wipe)' }) ..."
Start-Process -FilePath $exe -WorkingDirectory $work -WindowStyle Minimized -ArgumentList $sitlArgs
Start-Sleep -Seconds 3

# MAVProxy needs a real console window (it fails without one), so it gets its own.
$mavproxy = "C:\Program Files (x86)\MAVProxy\mavproxy.exe"
Start-Process -FilePath $mavproxy -WorkingDirectory $env:TEMP `
    -ArgumentList "--master=tcp:127.0.0.1:5760", "--out=udp:127.0.0.1:14550"
Write-Host "MAVProxy is the RC: type the pilot's commands in its window. GCS: UDP 14550, or TCP 127.0.0.1:5762."

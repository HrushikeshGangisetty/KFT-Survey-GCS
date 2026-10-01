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

  Several vehicles (-Count N, ADR-005): N instances of the same vehicle, instance i (0..N-1) with
    - system id i + 1 (SITL's --sysid, which sets MAV_SYSID);
    - every port above plus 10 * i (SITL's -I i): instance 1 is 5770 / 5772 / 5773, instance 2 is 5780 / 5782 / 5783;
    - home 10 m east of the one before, so they don't sit on top of each other;
    - its own MAVProxy window (its pilot's RC), all forwarding to the same UDP 14550.
  The GCS then reaches them either as ONE link carrying N vehicles ("SITL (UDP 14550)") or as N links with one vehicle
  each (TCP 5762, 5772, 5782). -SameSysId gives every instance system id 1: two of those on UDP 14550 is the
  "duplicate system id on one link" error, on purpose.

  Survey camera (Pass 16): camera.parm is loaded too. SITL keeps parameters in eeprom.bin, and a saved value wins
  over the defaults files, so run with -Wipe once per vehicle (or whenever the defaults change) to start fresh.

  The pilot (you) types into the MAVProxy window, e.g.
    Copter: mode guided -> arm throttle -> takeoff 20 -> mode auto
    Plane:  mode guided -> arm throttle -> takeoff 20 -> mode auto   (needs ArduPlane 4.5+ for GUIDED takeoff;
            on older firmware use: mode takeoff, then mode auto once it's climbing)

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\sitl\start-sitl.ps1 -Vehicle plane

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\sitl\start-sitl.ps1 -Count 3 -Wipe
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
    [switch]$Wipe,
    # How many vehicles to start (see "Several vehicles" above).
    [ValidateRange(1, 9)] [int]$Count = 1,
    # Every instance gets system id 1, to see the GCS report a duplicate system id.
    [switch]$SameSysId
)

$exe = Join-Path $SitlDir $(if ($Vehicle -eq "plane") { "ArduPlane.exe" } else { "ArduCopter.exe" })
if (-not (Test-Path $exe)) { throw "$exe not found. For Plane, start Plane SITL once from Mission Planner so it downloads it." }
if (-not $Defaults) { $Defaults = Join-Path $SitlDir "default_params\$Vehicle.parm" }
if (-not (Test-Path $Defaults)) { throw "Default parameters not found: $Defaults (pass -Defaults <path to $Vehicle.parm>)" }
# The survey camera (Pass 16): SITL reads a comma-separated list, later files winning.
$Defaults = "$Defaults," + (Join-Path $PSScriptRoot "camera.parm")
$model = if ($Vehicle -eq "plane") { "plane" } else { "quad" }

$mavproxy = "C:\Program Files (x86)\MAVProxy\mavproxy.exe"
$lat, $lon, $alt, $heading = $HomeLocation.Split(",")
# One degree of longitude is 111 319.5 m at the equator, shorter by cos(latitude) elsewhere.
$metresPerDegLon = 111319.5 * [Math]::Cos([double]$lat * [Math]::PI / 180)

for ($i = 0; $i -lt $Count; $i++) {
    # SITL keeps its parameters in eeprom.bin in the working folder. One folder per vehicle and instance, so Plane
    # never boots with Copter's saved parameters, nor instance 1 with instance 0's. The Cygwin DLLs are found next to the exe.
    $work = Join-Path $SitlDir $(if ($i -eq 0) { $Vehicle } else { "$Vehicle-i$i" })
    New-Item -ItemType Directory -Force $work | Out-Null

    $sysid = if ($SameSysId) { 1 } else { $i + 1 }
    $instanceHome = "$lat,$(([double]$lon + 10 * $i / $metresPerDegLon).ToString('F7', [Globalization.CultureInfo]::InvariantCulture)),$alt,$heading"
    $sitlArgs = @("--model", $model, "--home", $instanceHome, "--defaults", "`"$Defaults`"", "-I$i", "--sysid", $sysid)
    if ($Wipe) { $sitlArgs += "--wipe" }
    Write-Host "Starting $Vehicle SITL instance $i (system id $sysid) at $instanceHome$(if ($Wipe) { ' with fresh parameters (--wipe)' }) ..."
    Start-Process -FilePath $exe -WorkingDirectory $work -WindowStyle Minimized -ArgumentList $sitlArgs
    Start-Sleep -Seconds 3

    # MAVProxy needs a real console window (it fails without one), so each instance's gets its own.
    Start-Process -FilePath $mavproxy -WorkingDirectory $env:TEMP `
        -ArgumentList "--master=tcp:127.0.0.1:$(5760 + 10 * $i)", "--out=udp:127.0.0.1:14550"
}
Write-Host "MAVProxy is the RC: type the pilot's commands in its window (one window per vehicle)."
Write-Host "GCS: UDP 14550 (all $Count on one link), or TCP 127.0.0.1:$((0..($Count - 1) | ForEach-Object { 5762 + 10 * $_ }) -join ', ') (one link each)."

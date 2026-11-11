# QA helpers for adb-driven regression runs (debug builds). Usage:  . tools\qa.ps1
$global:adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$global:dev = if ($env:NG_DEV) { $env:NG_DEV } else { "R3CX80646FR" }
$global:pkg = "com.nesgames.app"
function A { & $adb -s $dev @args }
function Top { (A shell "dumpsys activity activities | grep topResumedActivity") -join "" }
# Refuse to touch the phone while the user is inside the app (every QA batch ends on the launcher).
function Guard { $t = Top; if ($t -match "com.nesgames.app") { throw "nesGames is in the foreground and this batch did not start it: user may be playing. Aborting. [$t]" } }
function Q([string]$cmd, [int]$n = 0, [string[]]$more = @()) { A shell am broadcast -a com.nesgames.app.QA -p $pkg --es cmd $cmd --ei n $n @more | Out-Null; Start-Sleep -Milliseconds 350 }
function Lg([string]$pat = ".", [int]$last = 40) { A logcat -d -s nesGamesQA:* nesGames:* nesGamesScan:* nesGamesCovers:* | Select-String $pat | Select -Last $last | % { ($_ -replace '^\S+ \S+ +\d+ +\d+ ', '') } }
function StartTest([string]$sys, [string]$path, [string[]]$more = @()) { A shell am force-stop $pkg; A logcat -c; A shell "am start -n $pkg/.ui.GameActivity --es system $sys --es test_rom_file '$path' $($more -join ' ')" | Out-Null }
function Shot([string]$name) { cmd /c "`"$adb`" -s $dev exec-out screencap -p > `"E:\ AndroidStudioProjects\nesGames\docs\qa\$name.png`"" }
function Home { A shell input keyevent HOME | Out-Null }

# Save/load proof for one slot: RAM hash at save (H1), after playing on (H2), after load (H3). PASS when H3 == H1 and H2 != H1.
function SaveCycle([int]$slot, [int]$inputMask = 128) {
    A logcat -c
    Q pause; Q save $slot; Q resume
    Q input $inputMask @("--ei","ms","1200"); Start-Sleep -Milliseconds 1800
    Q pause; Q ram; Q load $slot; Q ram; Q resume
    $l = (A logcat -d -s nesGamesQA:*) -join "`n"
    $save = [regex]::Match($l, "SAVE slot=$slot ok=(\w+) size=(\d+) ram=(\w+) thumb=(\S+)")
    $rams = [regex]::Matches($l, "QA ram=(\w+)")
    $load = [regex]::Match($l, "LOAD slot=$slot ok=(\w+) health=(\w+) ram=(\w+)")
    $h1 = $save.Groups[3].Value; $h2 = $rams[0].Groups[1].Value; $h3 = $load.Groups[3].Value
    $v = if ($save.Groups[1].Value -eq "true" -and $load.Groups[1].Value -eq "true" -and $h1 -eq $h3 -and $h1 -ne $h2 -and $h1) { "PASS" } else { "FAIL" }
    "slot=$slot save=$($save.Groups[1].Value) size=$($save.Groups[2].Value) thumb=$($save.Groups[4].Value) health=$($load.Groups[2].Value) H1=$h1 H2(after play)=$h2 H3(after load)=$h3 => $v"
}

# Auto-save on exit, then relaunch with resume=true: the RAM hash right after the restore must equal the hash at exit.
function AutoCycle([string]$sys, [string]$path) {
    A logcat -c; Q input 128 @("--ei","ms","800"); Start-Sleep 1; Q finish; Start-Sleep 4
    $l1 = (A logcat -d -s nesGamesQA:*) -join "`n"
    $save = [regex]::Match($l1, "SAVE slot=0 ok=(\w+) size=(\d+) ram=(\w+) thumb=(\S+)")
    A shell am force-stop $pkg; Start-Sleep 1     # full process death: nothing survives in memory
    StartTest $sys $path @("--ez","resume","true"); Start-Sleep 7
    $l2 = (A logcat -d -s nesGamesQA:*) -join "`n"
    $load = [regex]::Match($l2, "LOAD slot=0 ok=(\w+) health=(\w+) ram=(\w+)")
    $res = [regex]::Match($l2, "RESUME (\w+)").Groups[1].Value
    $run = [regex]::Matches($l2, "frames=(\d+)") | Select -Last 1
    $v = if ($save.Groups[1].Value -eq "true" -and $res -eq "OK" -and $save.Groups[3].Value -eq $load.Groups[3].Value -and $save.Groups[3].Value) { "PASS" } else { "FAIL" }
    "auto-save=$($save.Groups[1].Value) thumb=$($save.Groups[4].Value) exitRAM=$($save.Groups[3].Value) | relaunch RESUME=$res restoredRAM=$($load.Groups[3].Value) runningFrames=$($run.Groups[1].Value) => $v"
}

# Damaged-save handling on slot 3 of the running test game (key = BOOT_OK id).
function CorruptCycle([string]$key) {
    $d = "files/saves/$key"
    A logcat -c; Q pause; Q save 3; Q ram
    A shell "run-as $pkg sh -c 'cp $d/slot3.state $d/good.bak; head -c 1000 $d/good.bak > $d/slot3.state'"      # truncated, record present
    Q load 3; Q ram
    A shell "run-as $pkg sh -c 'rm $d/slot3.meta'"                                                               # truncated, no record (old-style)
    Q load 3; Q ram
    A shell "run-as $pkg sh -c 'cp $d/good.bak $d/slot3.state; rm $d/good.bak'"                                  # intact file from before, no record: legacy path
    Q load 3; Q ram; Q resume
    (A logcat -d -s nesGamesQA:* nesGames:*) | Select-String "SAVE slot=3|QA ram|LOAD slot=3|QA load|state rejected" | % { ($_ -replace '^\S+ \S+ +\d+ +\d+ ', '') }
}

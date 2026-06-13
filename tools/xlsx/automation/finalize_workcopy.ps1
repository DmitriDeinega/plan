# Produce a complete, ready-to-use copy: inject ALL VBA from source, build the
# layout, point at a chosen API, optionally populate via InitApp, and save.
# Default API = local dev. Pass -Api to override (prod when applying for real).
#
#   pwsh finalize_workcopy.ps1                                   # build dev copy at D:\tmp
#   pwsh finalize_workcopy.ps1 -Populate                         # + preload data from API
#   pwsh finalize_workcopy.ps1 -Workbook <path> -Api https://... # build against prod
#
# Typical flow for shipping: copy Plan.xlsm to a work path, finalize it with the
# prod -Api, verify, then replace tools/xlsx/Plan.xlsm with the result.
# Requires "Trust access to the VBA project object model" (Excel > Trust Center).
param(
  [string]$Workbook = 'D:\tmp\Plan.work.xlsm',
  [string]$SrcDir   = 'D:\Projects\plan\tools\xlsx\vba',
  [string]$Api      = 'http://localhost:2100/',
  [switch]$Populate                       # run InitApp to pre-load data
)
$ErrorActionPreference = 'Stop'

$xl = New-Object -ComObject Excel.Application
$xl.Visible = $true; $xl.DisplayAlerts = $false; $xl.AutomationSecurity = 1; $xl.EnableEvents = $false
$wb = $null

function FindComp($proj, $name) { foreach ($c in $proj.VBComponents) { if ($c.Name -eq $name) { return $c } }; return $null }

# Replace a document module's code in place: strip the exported header (everything up
# to and including the last "Attribute VB_" line) and add only the real code.
function ReplaceDocCode($comp, $src) {
  $lines = @(Get-Content $src)
  $hdr = -1
  for ($i = 0; $i -lt $lines.Count; $i++) { if ($lines[$i] -match '^Attribute VB_') { $hdr = $i } }
  if ($hdr -ge 0 -and $hdr -lt $lines.Count - 1) { $code = ($lines[($hdr + 1)..($lines.Count - 1)]) -join "`r`n" } else { $code = '' }
  $cm = $comp.CodeModule
  if ($cm.CountOfLines -gt 0) { $cm.DeleteLines(1, $cm.CountOfLines) }
  if ($code.Trim().Length -gt 0) { $cm.AddFromString($code) }
}

try {
  $wb = $xl.Workbooks.Open($Workbook)
  $proj = $wb.VBProject

  # inject EVERY source module so nothing can be silently stale. Document modules
  # (sheet code-behind, ThisWorkbook; Type 100) can't be removed -> replace code in
  # place; everything else is removed and re-imported.
  $files = Get-ChildItem $SrcDir -File | Where-Object { $_.Extension -in '.bas', '.cls', '.frm' }
  foreach ($f in $files) {
    $name = $f.BaseName
    $existing = FindComp $proj $name
    if ($existing -and [int]$existing.Type -eq 100) {
      ReplaceDocCode $existing $f.FullName
      Write-Output ("replaced code of " + $name)
    }
    else {
      if ($existing) { $proj.VBComponents.Remove($existing) }
      $proj.VBComponents.Import($f.FullName) | Out-Null
      Write-Output ("imported " + $name)
    }
  }

  $xl.Run("BuildPlanLayout")
  $xl.DisplayAlerts = $false   # BuildPlanLayout re-enables alerts; re-suppress for headless automation
  $xl.EnableEvents = $false
  $xl.Run("modPlan.SetApiRoute", $Api)
  Write-Output ("API Route -> " + $Api)

  if ($Populate) {
    $xl.EnableEvents = $false
    try { $xl.Run("InitApp") } catch { Write-Output ("InitApp error: " + $_.Exception.Message) }
    Write-Output "populated via InitApp"
  }

  $xl.EnableEvents = $false
  $wb.Save()
  $xl.EnableEvents = $false
  $wb.Close($false)
  $wb = $null
  Write-Output ("DONE -> " + $Workbook)
}
finally {
  if ($wb -ne $null) { try { $xl.EnableEvents = $false; $wb.Close($false) } catch {} }
  $xl.Quit(); [System.Runtime.Interopservices.Marshal]::ReleaseComObject($xl) | Out-Null; [GC]::Collect(); [GC]::WaitForPendingFinalizers()
}

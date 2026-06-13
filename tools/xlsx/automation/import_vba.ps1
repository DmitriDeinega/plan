# Inject the VBA source (tools/xlsx/vba/*.bas|.cls|.frm) back into a workbook.
# Inverse of export_vba.ps1. Standard/class/form modules are removed and re-imported;
# document modules (sheet code-behind, ThisWorkbook) can't be removed, so their code
# is replaced in place. Saves the workbook. Does NOT build the layout or touch the
# API route -- use finalize_workcopy.ps1 for a full build.
#
#   pwsh import_vba.ps1                        # vba/ -> Plan.xlsm
#   pwsh import_vba.ps1 -Workbook <path> -SrcDir <dir>
#
# Requires "Trust access to the VBA project object model" (Excel > Trust Center).
param(
  [string]$Workbook = 'D:\Projects\plan\tools\xlsx\Plan.xlsm',
  [string]$SrcDir   = 'D:\Projects\plan\tools\xlsx\vba'
)
$ErrorActionPreference = 'Stop'

$xl = New-Object -ComObject Excel.Application
$xl.Visible = $false; $xl.DisplayAlerts = $false; $xl.AutomationSecurity = 1; $xl.EnableEvents = $false
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
  $xl.EnableEvents = $false
  $wb.Save()
  $wb.Close($false)
  $wb = $null
  Write-Output ("DONE -> " + $Workbook)
}
finally {
  if ($wb -ne $null) { try { $xl.EnableEvents = $false; $wb.Close($false) } catch {} }
  $xl.Quit(); [System.Runtime.Interopservices.Marshal]::ReleaseComObject($xl) | Out-Null; [GC]::Collect(); [GC]::WaitForPendingFinalizers()
}

# Export every VBA component from the workbook into the source folder as .bas/.cls/.frm.
# Run this AFTER editing macros inside Excel's VBA editor so the readable, diffable
# source in tools/xlsx/vba/ stays in sync with the workbook. Inverse of import_vba.ps1.
#
#   pwsh export_vba.ps1                       # Plan.xlsm -> tools/xlsx/vba
#   pwsh export_vba.ps1 -Workbook <path> -OutDir <dir>
#
# Requires "Trust access to the VBA project object model" (Excel > Trust Center).
param(
  [string]$Workbook = 'D:\Projects\plan\tools\xlsx\Plan.xlsm',
  [string]$OutDir   = 'D:\Projects\plan\tools\xlsx\vba'
)
$ErrorActionPreference = 'Stop'

# VBComponent.Type -> file extension. 1=std module, 2=class, 3=MSForm, 100=document.
$extByType = @{ 1 = 'bas'; 2 = 'cls'; 3 = 'frm'; 100 = 'cls' }

$xl = New-Object -ComObject Excel.Application
$xl.Visible = $false; $xl.DisplayAlerts = $false; $xl.AutomationSecurity = 1; $xl.EnableEvents = $false
$wb = $null
try {
  if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir | Out-Null }
  $wb = $xl.Workbooks.Open($Workbook, $false, $true)   # read-only
  $proj = $wb.VBProject
  foreach ($c in $proj.VBComponents) {
    $ext = $extByType[[int]$c.Type]
    if (-not $ext) { $ext = 'bas' }
    $path = Join-Path $OutDir ($c.Name + '.' + $ext)
    $c.Export($path)
    Write-Output ("exported " + $c.Name + "." + $ext)
  }
  $wb.Close($false)
  $wb = $null
  Write-Output ("DONE -> " + $OutDir)
}
finally {
  if ($wb -ne $null) { try { $wb.Close($false) } catch {} }
  $xl.Quit(); [System.Runtime.Interopservices.Marshal]::ReleaseComObject($xl) | Out-Null; [GC]::Collect(); [GC]::WaitForPendingFinalizers()
}

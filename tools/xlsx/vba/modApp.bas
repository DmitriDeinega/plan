Attribute VB_Name = "modApp"
Option Explicit

Public Sub InitApp()
    On Error GoTo CleanUp
    Application.ScreenUpdating = False

    ' No API Route configured -> skip every server call. The address is deliberately left
    ' blank in the committed workbook so it can be filled in per machine after copying the
    ' folder; without this guard each of the three loads below would fail and raise an
    ' error dialog on open.
    If Not modApi.IsApiConfigured() Then
        Worksheets("Plan").Activate
        ActiveWindow.ScrollRow = 1
        ActiveWindow.ScrollColumn = 1
        ThisWorkbook.Saved = True
        GoTo CleanUp
    End If

    modDBSettings.GetDBSettings
    modFoods.GetFoods
    modDay.GetOpenDay

    ThisWorkbook.Saved = True
    Worksheets("Plan").Activate
    ' Reset the view to the very top-left on load.
    ActiveWindow.ScrollRow = 1
    ActiveWindow.ScrollColumn = 1

CleanUp:
    modError.ReportError "modApp.InitApp"
    Application.ScreenUpdating = True
End Sub


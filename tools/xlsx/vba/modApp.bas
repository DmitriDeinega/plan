Attribute VB_Name = "modApp"
Option Explicit

Public Sub InitApp()
    On Error GoTo CleanUp
    Application.ScreenUpdating = False

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


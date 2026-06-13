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

CleanUp:
    modError.ReportError "modApp.InitApp"
    Application.ScreenUpdating = True
End Sub


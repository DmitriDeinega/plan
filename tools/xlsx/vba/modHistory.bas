Attribute VB_Name = "modHistory"
Option Explicit

Public Sub GetWeights()
    On Error GoTo CleanUp
    Application.ScreenUpdating = False

    Dim weightsWeightColumn As Long
    Dim dayRow As Long
    Dim result As String
    Dim startDate As Date

    Dim json As Dictionary
    Dim weightDay As Variant

    Worksheets("Weights").Columns(2).ClearContents

    result = modApi.Execute(modApi.methodGet, "weights")
    Set json = JsonConverter.ParseJson(result)

    If json("status") <> "SUCCESS" Then
        MsgBox json("errorMessage")
        GoTo CleanUp
    End If

    ' parse dd/mm/yyyy text via ParseDmy (locale-safe; CDate would swap day/month)
    startDate = modPlan.ParseDmy(modSettings.GetSettingsDict("Start Date"))
    weightsWeightColumn = CLng(modSettings.GetSettingsDict("Weights Weight Column"))

    For Each weightDay In json("days")
        If CStr(weightDay("weight")) <> "0" Then
            dayRow = DateDiff("d", startDate, modPlan.ParseDmy(modDateUtils.DateParamToStr(weightDay("date")))) + 1
            Worksheets("Weights").Cells(dayRow, weightsWeightColumn).Value = weightDay("weight")
        End If
    Next weightDay

CleanUp:
    modError.ReportError "modHistory.GetWeights"
    Application.ScreenUpdating = True
End Sub

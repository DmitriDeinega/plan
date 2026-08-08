VERSION 1.0 CLASS
BEGIN
  MultiUse = -1  'True
END
Attribute VB_Name = "ThisWorkbook"
Attribute VB_GlobalNameSpace = False
Attribute VB_Creatable = False
Attribute VB_PredeclaredId = True
Attribute VB_Exposed = True
Option Explicit

Private Sub Workbook_Open()
    modApp.InitApp
    modUI.EnsurePlanProtection
End Sub

Private Sub Workbook_SheetActivate(ByVal Sh As Object)
    ' Same reason as Workbook_Open: with no API Route there is nothing to fetch.
    If Not modApi.IsApiConfigured() Then Exit Sub

    If Sh.name = "Weights" Then
        modHistory.GetWeights
    End If
End Sub

Private Sub Workbook_BeforeSave(ByVal SaveAsUI As Boolean, Cancel As Boolean)
    ' Saving pushes the sheet to the server. Unconfigured, that is a guaranteed failure on
    ' every Ctrl+S, so skip it and let the file save normally.
    If Not modApi.IsApiConfigured() Then Exit Sub

    If ActiveSheet.name = "Plan" Then
        modDay.SetDay
    ElseIf ActiveSheet.name = "Foods" Then
        modFoods.SetFoods
    End If
End Sub


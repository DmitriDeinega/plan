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
    If Sh.name = "Weights" Then
        modHistory.GetWeights
    End If
End Sub

Private Sub Workbook_BeforeSave(ByVal SaveAsUI As Boolean, Cancel As Boolean)
    If ActiveSheet.name = "Plan" Then
        modDay.SetDay
    ElseIf ActiveSheet.name = "Foods" Then
        modFoods.SetFoods
    End If
End Sub


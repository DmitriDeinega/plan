VERSION 1.0 CLASS
BEGIN
  MultiUse = -1  'True
END
Attribute VB_Name = "Sheet2"
Attribute VB_GlobalNameSpace = False
Attribute VB_Creatable = False
Attribute VB_PredeclaredId = True
Attribute VB_Exposed = True
Option Explicit

Private Sub Worksheet_Change(ByVal Target As Range)
    If Intersect(Target, Me.Range("A37")) Is Nothing Then Exit Sub
    If Target.CountLarge > 1 Then Exit Sub

    On Error GoTo CleanUp
    Application.EnableEvents = False

    If Target.Value = "11" Then
        modFoods.SetFoods
    ElseIf Target.Value = "22" Then
        modFoods.GetFoods
    End If

CleanUp:
    modError.ReportError "Sheet2.Worksheet_Change"
    Application.EnableEvents = True
End Sub


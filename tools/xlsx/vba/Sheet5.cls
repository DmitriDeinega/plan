VERSION 1.0 CLASS
BEGIN
  MultiUse = -1  'True
END
Attribute VB_Name = "Sheet5"
Attribute VB_GlobalNameSpace = False
Attribute VB_Creatable = False
Attribute VB_PredeclaredId = True
Attribute VB_Exposed = True
Option Explicit

Private Sub Worksheet_Change(ByVal Target As Range)
    If Target.Address(0, 0) = "F1" Then
        If Target.Value = "11" Then
            ' ThisWorkbook.SetDBSettings
        ElseIf Target.Value = "22" Then
            modDBSettings.GetDBSettings
        End If
    End If
End Sub

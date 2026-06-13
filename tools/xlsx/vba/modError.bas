Attribute VB_Name = "modError"
Option Explicit

Public Sub ReportError(ByVal context As String)
    Dim n As Long, d As String, s As String
    n = Err.Number
    d = Err.Description
    s = Err.Source
    If n <> 0 Then
        MsgBox "Err " & n & " in " & context & vbCrLf & _
               "Source: " & s & vbCrLf & d, _
               vbExclamation, "Plan error"
        Err.Clear
    End If
End Sub



Attribute VB_Name = "modState"
Option Explicit

Private dateChangeFromCode As Boolean
Private dayClosed As Boolean

Public Function GetDateChangeFromCode() As Boolean
    GetDateChangeFromCode = dateChangeFromCode
End Function

Public Sub SetDateChangeFromCode(ByVal v As Boolean)
    dateChangeFromCode = v
End Sub

Public Function GetDayClosed() As Boolean
    GetDayClosed = dayClosed
End Function

Public Sub SetDayClosed(ByVal v As Boolean)
    dayClosed = v
End Sub

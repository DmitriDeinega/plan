VERSION 1.0 CLASS
BEGIN
  MultiUse = -1  'True
END
Attribute VB_Name = "Sheet1"
Attribute VB_GlobalNameSpace = False
Attribute VB_Creatable = False
Attribute VB_PredeclaredId = True
Attribute VB_Exposed = True
Option Explicit

Private Sub Worksheet_Change(ByVal Target As Range)
    On Error GoTo CleanUp
    Application.EnableEvents = False

    ' Single cell special triggers
    If Target.CountLarge = 1 Then
        If Target.Address(0, 0) = "A30" Then
            If Target.Value = "11" Then
                modDay.SetDay
            ElseIf Target.Value = "22" Then
                modDay.GetDay
            End If
            GoTo CleanUp
        End If

        If Target.Address(0, 0) = "B1" Then
            If Not modState.GetDateChangeFromCode Then
                modDay.DayChanged
            End If
            GoTo CleanUp
        End If

    End If

    ' Multi-cell or normal edits: update formulas only when day is open
    If modState.GetDayClosed Then GoTo CleanUp

    ' collect the meals whose items changed
    Dim affected As Object: Set affected = CreateObject("Scripting.Dictionary")
    Dim cel As Range, sc As Long
    For Each cel In Target.Cells
        sc = modPlan.MealStartColOfCell(cel.row, cel.column)
        If sc > 0 Then affected(sc) = True
    Next cel

    If affected.Count > 0 Then
        Dim key As Variant
        For Each key In affected.Keys
            modPlan.CompactMeal CLng(key)   ' shift items up, drop fully-empty rows, reformula
        Next key
        Worksheets("Plan").Calculate
        modPlan.ApplyAllMealBorders         ' grow/shrink meal boxes to fit items
    End If

CleanUp:
    modError.ReportError "Sheet1.Worksheet_Change"
    Application.EnableEvents = True
End Sub

Private Sub Worksheet_Activate()
    modUI.EnsurePlanProtection
End Sub

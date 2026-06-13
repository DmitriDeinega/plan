Attribute VB_Name = "modPlanHelpers"
Option Explicit

' ============================================================================
' modPlanHelpers - food macro formulas, registry-driven (no hardcoded rows).
' For a food NAME cell, writes protein/fat/calories formulas to the +2/+3/+4
' columns of its meal block. Formula variant depends on the meal kind:
'   regular             -> 3-table nested VLOOKUP across the Foods categories
'   Fruits / Vegetables -> Carb category (Foods M:*) single lookup
'   Nuts                -> Fat category (Foods G:*) single lookup
' A regular-meal food whose name is a group ("Fruits"/"Nuts"/"Vegetables")
' takes its macros from that group block's totals row.
' ============================================================================

' SUM over a group block's item range for a macro (off: 2=protein,3=fat,4=cal).
' Position-independent so it doesn't depend on where the group's Sum row sits.
Private Function GroupTotalAddr(ByVal groupName As String, ByVal off As Long) As String
    Dim gc As Long
    gc = modPlan.MealStartColByName(groupName)
    GroupTotalAddr = modPlan.ColLetter(gc + off) & modPlan.FIXED_TOTAL_ROW
End Function

Public Sub SetFormulaForFood(ByVal Target As Range)
    Dim ws As Worksheet
    Set ws = Worksheets("Plan")

    Dim startCol As Long
    startCol = modPlan.MealStartColOfCell(Target.row, Target.column)
    If startCol = 0 Then Exit Sub
    ' Only the food NAME cell (block start column) drives the formula
    If Target.column <> startCol Then Exit Sub

    Dim mealName As String
    mealName = CStr(ws.Cells(modPlan.NAME_ROW, startCol).Value)
    Dim kind As String
    kind = modPlan.MealKind(mealName)

    Dim r As Long: r = Target.row
    Dim foodCol As String: foodCol = modPlan.ColLetter(startCol)
    Dim wCol As String: wCol = modPlan.ColLetter(startCol + 1)
    Dim proteinCol As Long: proteinCol = startCol + 2
    Dim fatCol As Long: fatCol = startCol + 3
    Dim caloriesCol As Long: caloriesCol = startCol + 4

    Dim fRef As String: fRef = foodCol & r      ' food name cell
    Dim wRef As String: wRef = wCol & r         ' weight cell

    Dim pF As String, fF As String, cF As String

    If Target.Value = "" Then
        ws.Cells(r, proteinCol).Value = ""
        ws.Cells(r, fatCol).Value = ""
        ws.Cells(r, caloriesCol).Value = ""
        Exit Sub
    ElseIf Target.Value = "Fruits" Or Target.Value = "Nuts" Or Target.Value = "Vegetables" Then
        ' group-as-food: macros come from that group block's totals
        pF = GroupTotalAddr(CStr(Target.Value), 2)
        fF = GroupTotalAddr(CStr(Target.Value), 3)
        cF = GroupTotalAddr(CStr(Target.Value), 4)
    ElseIf kind = "regular" Then
        pF = RegularMacro(fRef, wRef, "A:B", "G:H", "M:N", 2)
        fF = RegularMacro(fRef, wRef, "A:C", "G:I", "M:O", 3)
        cF = RegularMacro(fRef, wRef, "A:D", "G:J", "M:P", 4)
    ElseIf kind = "Nuts" Then
        pF = SingleMacro(fRef, wRef, "G:H", 2)
        fF = SingleMacro(fRef, wRef, "G:I", 3)
        cF = SingleMacro(fRef, wRef, "G:J", 4)
    Else  ' Fruits / Vegetables -> Carb category
        pF = SingleMacro(fRef, wRef, "M:N", 2)
        fF = SingleMacro(fRef, wRef, "M:O", 3)
        cF = SingleMacro(fRef, wRef, "M:P", 4)
    End If

    ws.Cells(r, proteinCol).Formula = "=" & pF
    ws.Cells(r, fatCol).Formula = "=" & fF
    ws.Cells(r, caloriesCol).Formula = "=" & cF
End Sub

' 3-table nested lookup across the Protein/Fat/Carb Foods categories.
Private Function RegularMacro(ByVal fRef As String, ByVal wRef As String, _
    ByVal t1 As String, ByVal t2 As String, ByVal t3 As String, _
    ByVal idx As Long) As String
    Dim s As String
    s = "IF(ISBLANK(" & fRef & "),"""", ROUND(" & _
        "IF(ISNA(VLOOKUP(" & fRef & ",Foods!" & t1 & "," & idx & ",FALSE))," & _
          "IF(ISNA(VLOOKUP(" & fRef & ",foods!" & t2 & "," & idx & ",FALSE))," & _
            "IF(ISNA(VLOOKUP(" & fRef & ",foods!" & t3 & "," & idx & ",FALSE)),""NONE""," & _
              "(" & wRef & "/100)*VLOOKUP(" & fRef & ",foods!" & t3 & "," & idx & ",FALSE))," & _
            "(" & wRef & "/100)*VLOOKUP(" & fRef & ",foods!" & t2 & "," & idx & ",FALSE))," & _
          "(" & wRef & "/100)*VLOOKUP(" & fRef & ",foods!" & t1 & "," & idx & ",FALSE)), 2))"
    RegularMacro = s
End Function

' Single-table lookup (matches the original group-meal formula).
Private Function SingleMacro(ByVal fRef As String, ByVal wRef As String, _
    ByVal tbl As String, ByVal idx As Long) As String
    SingleMacro = "IF(ISBLANK(" & fRef & "),"""", ROUND(" & wRef & "*VLOOKUP(" & fRef & ",Foods!" & tbl & "," & idx & ",FALSE)/100, 2))"
End Function

Public Sub RebuildAllFoodFormulas()
    Dim ws As Worksheet
    Set ws = Worksheets("Plan")

    Dim cols As Variant: cols = modPlan.MealCols()
    Dim i As Long, r As Long, sc As Long
    Dim lastR As Long: lastR = modPlan.ItemLastRow()

    For i = LBound(cols) To UBound(cols)
        sc = CLng(cols(i))
        For r = modPlan.ITEM_FIRST_ROW To lastR
            SetFormulaForFood ws.Cells(r, sc)
        Next r
    Next i

    ws.Calculate
End Sub

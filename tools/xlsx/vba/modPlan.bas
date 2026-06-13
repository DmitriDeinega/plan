Attribute VB_Name = "modPlan"
Option Explicit

' ============================================================================
' modPlan - meal-block registry + one-time Plan-sheet layout builder.
' Geometry single source of truth. The rest of the app reads positions from the
' Settings sheet (which BuildPlanLayout writes), so changing geometry here +
' re-running BuildPlanLayout keeps everything consistent. See NEW_LAYOUT_SPEC.md.
' ============================================================================

Public Const NAME_ROW As Long = 1
Public Const HEADER_ROW As Long = 2
Public Const ITEM_FIRST_ROW As Long = 3
Public Const ITEM_CAPACITY As Long = 50
Public Const FIXED_TOTAL_ROW As Long = 60   ' hidden per-meal totals (excluded from item range; avoids circular refs)
Public Const DB_SHEET As String = "DBSettings"

Public Function ItemLastRow() As Long
    ItemLastRow = ITEM_FIRST_ROW + ITEM_CAPACITY - 1   ' 52
End Function

' 8 meals in order, with start columns and sign-checkbox names.
Public Function MealNames() As Variant
    MealNames = Array("Meal 1", "Meal 2", "Meal 3", "Meal 4", "Meal 5", "Fruits", "Nuts", "Vegetables")
End Function
' Column E (5) is an empty spacer; meal blocks start at F (6).
Public Function MealCols() As Variant
    MealCols = Array(6, 12, 18, 24, 30, 36, 42, 48)
End Function
Public Function MealCbNames() As Variant
    MealCbNames = Array("cbSign1", "cbSign2", "cbSign3", "cbSign4", "cbSign5", "cbSignF", "cbSignN", "cbSignV")
End Function

Public Function ColLetter(ByVal c As Long) As String
    ColLetter = Split(Worksheets("Plan").Cells(1, c).Address(True, False), "$")(0)
End Function

' Start column of a meal by its name (0 if not found).
Public Function MealStartColByName(ByVal mealName As String) As Long
    Dim names As Variant: names = MealNames()
    Dim cols As Variant: cols = MealCols()
    Dim i As Long
    For i = LBound(names) To UBound(names)
        If CStr(names(i)) = mealName Then MealStartColByName = CLng(cols(i)): Exit Function
    Next i
End Function

' Kind of a meal by name: "regular" | "Fruits" | "Nuts" | "Vegetables"
Public Function MealKind(ByVal mealName As String) As String
    Select Case mealName
        Case "Fruits", "Nuts", "Vegetables": MealKind = mealName
        Case Else: MealKind = "regular"
    End Select
End Function

' Given any cell, return the start column of the meal block whose item area it is
' in (name or weight col), else 0. Used by Worksheet_Change.
Public Function MealStartColOfCell(ByVal r As Long, ByVal c As Long) As Long
    Dim cols As Variant: cols = MealCols()
    Dim i As Long
    If r < ITEM_FIRST_ROW Or r > ItemLastRow() Then Exit Function
    For i = LBound(cols) To UBound(cols)
        Dim sc As Long: sc = CLng(cols(i))
        If c = sc Or c = sc + 1 Then    ' name col or weight col
            MealStartColOfCell = sc
            Exit Function
        End If
    Next i
End Function

' A formula fragment that fetches a DB-loaded value from the DBSettings A/B table
' by name (column A = key, column B = value), robust to row position.
Private Function SRef(ByVal key As String) As String
    SRef = "INDEX(" & DB_SHEET & "!B:B,MATCH(""" & key & """," & DB_SHEET & "!A:A,0))"
End Function

' Colour a "Left" cell: green when the goal is met (goodOp vs 0), red otherwise.
' Colours match the original workbook: green #92D050, red #FF0000.
Private Sub ColorLeftCell(ByVal rng As Range, ByVal goodOp As XlFormatConditionOperator)
    rng.FormatConditions.Delete
    rng.Interior.Color = RGB(255, 0, 0)           ' default: red (goal not met)
    Dim fc As FormatCondition
    Set fc = rng.FormatConditions.Add(Type:=xlCellValue, Operator:=goodOp, Formula1:="0")
    fc.Interior.Color = RGB(146, 208, 80)         ' green (goal met)
End Sub

' Return a worksheet by name, creating it (after Settings) if missing.
Public Function EnsureSheet(ByVal name As String) As Worksheet
    Dim wsx As Worksheet
    For Each wsx In ThisWorkbook.Worksheets
        If wsx.name = name Then Set EnsureSheet = wsx: Exit Function
    Next wsx
    Set EnsureSheet = ThisWorkbook.Worksheets.Add(After:=Worksheets("Settings"))
    EnsureSheet.name = name
End Function

' Delete a key (and its row) from an A/B settings table, if present.
Private Sub RemoveSetting(ByVal ws As Worksheet, ByVal key As String)
    Dim i As Long: i = 1
    Do While CStr(ws.Cells(i, 1).Value) <> ""
        If CStr(ws.Cells(i, 1).Value) = key Then ws.Rows(i).Delete: Exit Sub
        i = i + 1
    Loop
End Sub

' Move a key/value from one A/B sheet to another (delete the source row so the
' source table stays gap-free), preserving the value.
Private Sub MoveSetting(ByVal fromWs As Worksheet, ByVal toWs As Worksheet, ByVal key As String)
    Dim i As Long: i = 1
    Do While CStr(fromWs.Cells(i, 1).Value) <> ""
        If CStr(fromWs.Cells(i, 1).Value) = key Then
            SetSetting toWs, key, fromWs.Cells(i, 2).Value
            fromWs.Rows(i).Delete
            Exit Sub
        End If
        i = i + 1
    Loop
End Sub

Private Sub SetSetting(ByVal ws As Worksheet, ByVal key As String, ByVal val As Variant)
    Dim i As Long: i = 1
    Do While CStr(ws.Cells(i, 1).Value) <> ""
        If CStr(ws.Cells(i, 1).Value) = key Then
            ws.Cells(i, 2).Value = val
            Exit Sub
        End If
        i = i + 1
    Loop
    ws.Cells(i, 1).Value = key
    ws.Cells(i, 2).Value = val
End Sub

Private Sub WriteSettingsGeometry(ByVal sset As Worksheet)
    Dim names As Variant: names = MealNames()
    Dim cols As Variant: cols = MealCols()
    Dim i As Long
    For i = LBound(names) To UBound(names)
        SetSetting sset, CStr(names(i)) & " Row", NAME_ROW
        SetSetting sset, CStr(names(i)) & " Column", CLng(cols(i))
    Next i
    SetSetting sset, "Meals Addition Row", ITEM_FIRST_ROW - NAME_ROW   ' 3
    ' left-pane geometry that IS read at runtime
    SetSetting sset, "Date Row", 1: SetSetting sset, "Date Column", 2          ' B1 (merged B1:C1)
    SetSetting sset, "Person Weight Row", 2: SetSetting sset, "Person Weight Column", 3   ' C2
    SetSetting sset, "Day Closed Status Row", 1: SetSetting sset, "Day Closed Status Column", 4   ' D1 (shares with > button)

    ' drop orphaned keys from earlier designs (dashboard is hard-coded B5:D7 now;
    ' stats live on DBSettings; Hist + Extra features removed)
    Dim orphan As Variant
    For Each orphan In Array( _
        "Daily Protein Row", "Daily Protein Column", "Daily Fat Row", "Daily Fat Column", _
        "Daily Calories Row", "Daily Calories Column", "Needed Protein Row", "Needed Protein Column", _
        "Needed Fat Row", "Needed Fat Column", "Needed Calories Row", "Needed Calories Column", _
        "Left Protein Row", "Left Protein Column", "Left Fat Row", "Left Fat Column", _
        "Left Calories Row", "Left Calories Column", "Person Height Row", "Person Height Column", _
        "Person Birthday Row", "Person Birthday Column", "Person Gender Row", "Person Gender Column", _
        "TDEE Multiplier Row", "TDEE Multiplier Column", "Hist Rows Per Day", _
        "Hist Addition Total Sum Rows", "Foods Extra Column")
        RemoveSetting sset, CStr(orphan)
    Next orphan
End Sub

' DBSettings sheet holds everything loaded from the DB (separate from the static
' layout config on Settings). modDBSettings populates the values on load; keys are
' created here so the dashboard INDEX/MATCH refs resolve before the first load.
Private Sub SetupDBSettings(ByVal sset As Worksheet, ByVal dbs As Worksheet)
    MoveSetting sset, dbs, "Start Date"          ' relocate the one existing DB value
    EnsureKey dbs, "Start Date"
    EnsureKey dbs, "Daily Protein perKg"
    EnsureKey dbs, "Daily Fat perKg"
    EnsureKey dbs, "Daily Calories"
    EnsureKey dbs, "TDEE Multiplier"
    EnsureKey dbs, "Calorie Type"
    EnsureKey dbs, "Height"
    EnsureKey dbs, "Gender"
    EnsureKey dbs, "Birthday"
    EnsureKey dbs, "Nuts New Day Amount"
    EnsureKey dbs, "Fruits New Day Amount"
    EnsureKey dbs, "Vegetables New Day Amount"
    dbs.Columns(1).ColumnWidth = 22
End Sub

' Create a settings key (blank value) if it doesn't already exist.
Private Sub EnsureKey(ByVal ws As Worksheet, ByVal key As String)
    Dim i As Long: i = 1
    Do While CStr(ws.Cells(i, 1).Value) <> ""
        If CStr(ws.Cells(i, 1).Value) = key Then Exit Sub
        i = i + 1
    Loop
    ws.Cells(i, 1).Value = key
End Sub

' Point the workbook at a given API base (e.g. http://localhost:2100/ for dev).
Public Sub SetApiRoute(ByVal url As String)
    SetSetting Worksheets("Settings"), "API Route", url
    On Error Resume Next
    modSettings.ResetSettingsDict
End Sub

Public Sub BuildPlanLayout()
    On Error GoTo Fail
    Application.ScreenUpdating = False
    Application.EnableEvents = False

    Dim ws As Worksheet: Set ws = Worksheets("Plan")
    Dim sset As Worksheet: Set sset = Worksheets("Settings")
    Dim dbs As Worksheet: Set dbs = EnsureSheet(DB_SHEET)

    WriteSettingsGeometry sset
    SetupDBSettings sset, dbs

    ' remove the Hist sheet (feature removed)
    On Error Resume Next
    Application.DisplayAlerts = False
    ThisWorkbook.Worksheets("Hist").Delete
    Application.DisplayAlerts = True
    ' clear the removed "Extra"/"Cheat" food category from the Foods sheet (S:X)
    ThisWorkbook.Worksheets("Foods").Columns("S:X").ClearContents
    On Error GoTo Fail
    ' invalidate cached settings dict so the rest of the app sees new geometry
    On Error Resume Next
    modSettings.ResetSettingsDict
    On Error GoTo Fail

    Dim dateVal As Variant: dateVal = ws.Range("B1").Value
    If IsEmpty(dateVal) Or dateVal = "" Then dateVal = ws.Range("A1").Value   ' migrate old date at A1
    ws.Unprotect Password:=""
    ws.Cells.Clear           ' clears cell content/format; shapes (checkboxes/buttons) survive
    ws.Cells.EntireColumn.Hidden = False   ' reset any collapsed-meal state
    ws.Cells.Font.size = 12
    ws.Cells.Font.name = "Calibri"
    ws.Cells.HorizontalAlignment = xlCenter
    ws.Cells.VerticalAlignment = xlCenter

    ' ---- left pane (cols A:D, E is a spacer) ----
    ' Row 1: [<] B1=date [>]  D1=status
    ' Date MUST be text, else Excel reinterprets dd/mm strings where day<=12 as
    ' mm/dd dates (locale), swapping day/month and breaking date parsing.
    ws.Range("B1:C1").Merge
    ws.Range("B1").NumberFormat = "@"
    ws.Range("B1").Value = CStr(dateVal)
    ws.Range("B1").HorizontalAlignment = xlCenter
    ws.Range("B1").Font.Bold = True
    ' Row 2: Weight label/value
    ws.Range("B2").Value = "Weight"
    ws.Range("B2").Font.Bold = True
    ' Row 4: dashboard headers ; rows 5-7: macros
    ws.Range("B4").Value = "Daily"
    ws.Range("C4").Value = "Needed"
    ws.Range("D4").Value = "Left"
    ws.Range("A5").Value = "Protein"
    ws.Range("A6").Value = "Fat"
    ws.Range("A7").Value = "Calories"
    ws.Range("B4:D4").Font.Bold = True
    ws.Range("A5:A7").Font.Bold = True

    ' Daily (consumed) = sum of the 5 regular meals' item ranges (position-independent,
    ' so it doesn't depend on where each meal's Sum row currently sits)
    Dim cols As Variant: cols = MealCols()
    Dim pCells As String, fCells As String, cCells As String
    Dim i As Long, sc As Long
    For i = 0 To 4   ' regular meals only
        sc = CLng(cols(i))
        If i > 0 Then pCells = pCells & ",": fCells = fCells & ",": cCells = cCells & ","
        pCells = pCells & ColLetter(sc + 2) & FIXED_TOTAL_ROW
        fCells = fCells & ColLetter(sc + 3) & FIXED_TOTAL_ROW
        cCells = cCells & ColLetter(sc + 4) & FIXED_TOTAL_ROW
    Next i
    ws.Range("B5").Formula = "=ROUND(SUM(" & pCells & "),2)"
    ws.Range("B6").Formula = "=ROUND(SUM(" & fCells & ")*9,2)"
    ws.Range("B7").Formula = "=ROUND(SUM(" & cCells & "),2)"

    ' Needed (target): values fetched by name from the Settings A/B table; weight C2.
    ' BMR/TDEE folded inline (not shown anywhere). Left = Needed - Daily.
    ' age computed inline from Birthday vs the shown date (B1); DATE(RIGHT/MID/LEFT)
    ' parses the dd/mm/yyyy text locale-independently.
    Dim bd As String: bd = SRef("Birthday")
    Dim ageExpr As String
    ageExpr = "DATEDIF(DATE(RIGHT(" & bd & ",4),MID(" & bd & ",4,2),LEFT(" & bd & ",2))," & _
              "DATE(RIGHT(B1,4),MID(B1,4,2),LEFT(B1,2)),""Y"")"
    Dim bmr As String, tdee As String
    bmr = "(10*C2+6.25*" & SRef("Height") & "-5*" & ageExpr & "+IF(" & SRef("Gender") & "=""M"",5,-161))"
    tdee = "(" & bmr & "*" & SRef("TDEE Multiplier") & ")"
    ws.Range("C5").Formula = "=ROUND(" & SRef("Daily Protein perKg") & "*C2,2)"
    ws.Range("C7").Formula = "=ROUND(IF(" & SRef("Calorie Type") & "=""surplus""," & tdee & "+" & SRef("Daily Calories") & "," & tdee & "-" & SRef("Daily Calories") & "),2)"
    ws.Range("C6").Formula = "=ROUND(C7*" & SRef("Daily Fat perKg") & ",2)"
    ws.Range("D5").Formula = "=ROUND(C5-B5,2)"
    ws.Range("D6").Formula = "=ROUND(C6-B6,2)"
    ws.Range("D7").Formula = "=ROUND(C7-B7,2)"
    ' colour Left: protein good (green) when <= 0; fat & calories good when >= 0; else red
    ColorLeftCell ws.Range("D5"), xlLessEqual
    ColorLeftCell ws.Range("D6"), xlGreaterEqual
    ColorLeftCell ws.Range("D7"), xlGreaterEqual

    ws.Range("A1:D7").Borders.LineStyle = xlContinuous
    ws.Range("A1:D7").Borders(xlEdgeRight).LineStyle = xlNone   ' no vertical line at the sticky edge
    ' strip inner borders from specific left-pane regions
    ws.Range("B5:D7").Borders(xlInsideVertical).LineStyle = xlNone
    ws.Range("B5:D7").Borders(xlInsideHorizontal).LineStyle = xlNone
    ws.Range("A3:D3").Borders(xlInsideVertical).LineStyle = xlNone
    ws.Range("A2:A4").Borders(xlInsideHorizontal).LineStyle = xlNone
    ws.Range("B2:C2").Borders(xlInsideVertical).LineStyle = xlNone
    ws.Range("A5:A7").Borders(xlInsideHorizontal).LineStyle = xlNone   ' #1
    ws.Range("B4:D4").Borders(xlInsideVertical).LineStyle = xlNone     ' #2
    ws.Range("D4:D7").Borders(xlEdgeRight).LineStyle = xlContinuous    ' right border on Left column
    ' #4: D1:D2 keep only the left border
    With ws.Range("D1:D2")
        .Borders(xlEdgeTop).LineStyle = xlNone
        .Borders(xlEdgeRight).LineStyle = xlNone
        .Borders(xlEdgeBottom).LineStyle = xlNone
        .Borders(xlInsideHorizontal).LineStyle = xlNone
        .Borders(xlEdgeLeft).LineStyle = xlContinuous
    End With
    EnsureNavButtons ws    ' < / > day-navigation buttons

    ' ---- 8 meal blocks ----
    Dim names As Variant: names = MealNames()
    Dim hdr As Variant: hdr = Array("", "Weight", "Protein", "Fat", "Calories")
    Dim lastR As Long: lastR = ItemLastRow()
    Dim k As Long
    For i = LBound(names) To UBound(names)
        sc = CLng(cols(i))
        ws.Cells(NAME_ROW, sc).Value = CStr(names(i))
        ws.Range(ws.Cells(NAME_ROW, sc), ws.Cells(NAME_ROW, sc + 4)).Merge
        ws.Cells(NAME_ROW, sc).HorizontalAlignment = xlCenter
        ws.Cells(NAME_ROW, sc).Font.Bold = True
        For k = 0 To 4
            ws.Cells(HEADER_ROW, sc + k).Value = hdr(k)
            ws.Cells(HEADER_ROW, sc + k).Font.Bold = True
        Next k
        ws.Range(ws.Cells(ITEM_FIRST_ROW, sc + 1), ws.Cells(lastR, sc + 1)).NumberFormat = "0"
        ws.Range(ws.Cells(ITEM_FIRST_ROW, sc), ws.Cells(lastR, sc)).Font.Bold = True   ' bold food names
        ' hidden per-meal totals are written by ApplyMealBorders (exact item range)
        ' borders are drawn dynamically by ApplyAllMealBorders (fit to item count)
        ws.Columns(sc).ColumnWidth = 16
        ws.Columns(sc + 1).ColumnWidth = 8
        ws.Columns(sc + 2).ColumnWidth = 8
        ws.Columns(sc + 3).ColumnWidth = 8
        ws.Columns(sc + 4).ColumnWidth = 9
        ws.Columns(sc + 5).ColumnWidth = 2.5
    Next i
    ws.Columns(1).ColumnWidth = 9      ' A: < button / macro labels
    ws.Columns(2).ColumnWidth = 10.73  ' B: Daily / date / weight label
    ws.Columns(3).ColumnWidth = 9      ' C: Needed / weight value
    ws.Columns(4).ColumnWidth = 9      ' D: Left / status
    ws.Columns(5).ColumnWidth = 2.5    ' E: empty spacer (not sticky)
    ws.Rows(FIXED_TOTAL_ROW).Hidden = True   ' hide the totals helper row

    ' ---- reposition sign checkboxes + buttons ----
    Dim cbs As Variant: cbs = MealCbNames()
    For i = LBound(names) To UBound(names)
        sc = CLng(cols(i))
        On Error Resume Next
        With ws.CheckBoxes(CStr(cbs(i)))
            .Placement = xlFreeFloating   ' survive column hide/show on collapse
            .Top = ws.Cells(NAME_ROW, sc + 4).Top + 1
            .Left = ws.Cells(NAME_ROW, sc + 4).Left + 2
        End With
        On Error GoTo Fail
    Next i
    On Error Resume Next
    ws.Buttons("btnEnd").Top = ws.Cells(9, 1).Top: ws.Buttons("btnEnd").Left = ws.Cells(9, 1).Left
    ws.Buttons("btnRevert").Top = ws.Cells(10, 1).Top: ws.Buttons("btnRevert").Left = ws.Cells(10, 1).Left
    On Error GoTo Fail

    ' ---- freeze cols A:E (left pane + spacer stick on horizontal scroll) ----
    ws.Activate
    ws.Cells(1, 1).Select
    With ActiveWindow
        .SplitColumn = 5
        .SplitRow = 0
        .FreezePanes = True
    End With

    ApplyAllMealBorders   ' fit each meal's border to its (currently empty) items

    ws.Protect Password:="", UserInterfaceOnly:=True

    Application.EnableEvents = True
    Application.ScreenUpdating = True
    Exit Sub
Fail:
    Application.EnableEvents = True
    Application.ScreenUpdating = True
    ' Re-raise so a COM caller (build harness) gets the error instead of a modal
    ' MsgBox that would hang a headless/invisible Excel run.
    Err.Raise Err.Number, "modPlan.BuildPlanLayout", Err.Description
End Sub

' Compact a meal's items: keep every row that has a name OR weight, packed
' contiguously from the top; rows where both are empty are dropped (items below
' shift up). Re-applies the macro formula for each surviving row.
Public Sub CompactMeal(ByVal startCol As Long)
    Dim ws As Worksheet: Set ws = Worksheets("Plan")
    Dim nm(1 To ITEM_CAPACITY) As String
    Dim wt(1 To ITEM_CAPACITY) As Variant
    Dim cnt As Long: cnt = 0
    Dim r As Long, nameV As String, wV As String
    For r = ITEM_FIRST_ROW To ITEM_FIRST_ROW + ITEM_CAPACITY - 1
        nameV = CStr(ws.Cells(r, startCol).Value)
        If nameV = "Sum" Then Exit For
        wV = CStr(ws.Cells(r, startCol + 1).Value)
        If nameV <> "" Or wV <> "" Then
            cnt = cnt + 1
            nm(cnt) = nameV
            wt(cnt) = ws.Cells(r, startCol + 1).Value
        End If
    Next r
    For r = 1 To ITEM_CAPACITY
        Dim rr As Long: rr = ITEM_FIRST_ROW + r - 1
        If r <= cnt Then
            ws.Cells(rr, startCol).Value = nm(r)
            ws.Cells(rr, startCol + 1).Value = wt(r)
        Else
            ws.Cells(rr, startCol).Value = ""
            ws.Cells(rr, startCol + 1).Value = ""
        End If
    Next r
    ' re-apply macro formulas for the surviving items (+ the now-blank open row)
    For r = ITEM_FIRST_ROW To ITEM_FIRST_ROW + cnt
        modPlanHelpers.SetFormulaForFood ws.Cells(r, startCol)
    Next r
End Sub

' A row counts as an item while it has a name OR a weight (so clearing just the
' name doesn't drop a row that still has a weight). Stops at the Sum row.
Private Function CountMealItems(ByVal ws As Worksheet, ByVal startCol As Long) As Long
    Dim r As Long: r = ITEM_FIRST_ROW
    Do While CStr(ws.Cells(r, startCol).Value) <> "Sum" And _
             (CStr(ws.Cells(r, startCol).Value) <> "" Or CStr(ws.Cells(r, startCol + 1).Value) <> "")
        r = r + 1
    Loop
    CountMealItems = r - ITEM_FIRST_ROW
End Function

' Lay out a meal to fit its items: dynamic box, a Sum row just below it that moves
' with the box, and locking of everything past the editable area.
'   Open meal : box = items + one empty row ; Sum on the row after the box.
'   Signed    : box = items only (border moves up) ; Sum on the row after.
Private Sub ApplyMealBorders(ByVal ws As Worksheet, ByVal startCol As Long, ByVal signed As Boolean)
    Dim n As Long: n = CountMealItems(ws, startCol)
    Dim lastRegion As Long: lastRegion = ITEM_FIRST_ROW + ITEM_CAPACITY + 2   ' clear bound

    Dim boxLast As Long
    If signed Then
        boxLast = ITEM_FIRST_ROW + n - 1
        If boxLast < HEADER_ROW Then boxLast = HEADER_ROW
    Else
        boxLast = ITEM_FIRST_ROW + n            ' one empty row for the next item
    End If
    Dim sumRow As Long: sumRow = boxLast + 1

    ' wipe borders for the whole region, and clear all rows from the first non-item
    ' row down (open row, old Sum, stale formulas) so nothing lingers when it moves
    Dim firstClear As Long: firstClear = ITEM_FIRST_ROW + n
    ws.Range(ws.Cells(NAME_ROW, startCol), ws.Cells(lastRegion, startCol + 4)).Borders.LineStyle = xlNone
    ws.Range(ws.Cells(firstClear, startCol), ws.Cells(lastRegion, startCol + 4)).ClearContents

    ' hidden fixed totals = SUM over the EXACT item rows only (excludes the Sum row,
    ' so no circular reference); the visible Sum row + dashboard reference these.
    Dim lastItem As Long: lastItem = ITEM_FIRST_ROW + n - 1
    Dim off2 As Long
    For off2 = 2 To 4
        If n > 0 Then
            ws.Cells(FIXED_TOTAL_ROW, startCol + off2).Formula = "=ROUND(SUM(" & _
                ColLetter(startCol + off2) & ITEM_FIRST_ROW & ":" & ColLetter(startCol + off2) & lastItem & "),2)"
        Else
            ws.Cells(FIXED_TOTAL_ROW, startCol + off2).Value = 0
        End If
    Next off2

    ' item box + its top border
    ws.Range(ws.Cells(NAME_ROW, startCol), ws.Cells(boxLast, startCol + 4)).BorderAround LineStyle:=xlContinuous
    ws.Range(ws.Cells(NAME_ROW, startCol), ws.Cells(NAME_ROW, startCol + 4)).Borders(xlEdgeTop).LineStyle = xlContinuous

    ' Sum row: "Sum" (bold) in the name col + the meal's totals (mirroring the hidden
    ' fixed-total row, so no self-referential SUM); own box, no inner lines
    ws.Cells(sumRow, startCol).Value = "Sum"
    ws.Cells(sumRow, startCol).Font.Bold = True
    ws.Cells(sumRow, startCol + 2).Formula = "=" & ColLetter(startCol + 2) & FIXED_TOTAL_ROW
    ws.Cells(sumRow, startCol + 3).Formula = "=" & ColLetter(startCol + 3) & FIXED_TOTAL_ROW
    ws.Cells(sumRow, startCol + 4).Formula = "=" & ColLetter(startCol + 4) & FIXED_TOTAL_ROW
    ws.Range(ws.Cells(sumRow, startCol), ws.Cells(sumRow, startCol + 4)).BorderAround LineStyle:=xlContinuous

    ' editability: only the items + open row (name & weight) are unlocked
    ws.Range(ws.Cells(ITEM_FIRST_ROW, startCol), ws.Cells(lastRegion, startCol + 1)).Locked = True
    If Not signed And Not modState.GetDayClosed() Then
        ws.Range(ws.Cells(ITEM_FIRST_ROW, startCol), ws.Cells(boxLast, startCol + 1)).Locked = False
    End If
End Sub

' Redraw every meal's border to fit its current item count + sign state.
Public Sub ApplyAllMealBorders()
    On Error Resume Next
    Dim ws As Worksheet: Set ws = Worksheets("Plan")
    Dim cols As Variant: cols = MealCols()
    Dim cbs As Variant: cbs = MealCbNames()
    Dim i As Long
    Dim prevSU As Boolean: prevSU = Application.ScreenUpdating
    Dim prevEv As Boolean: prevEv = Application.EnableEvents
    Application.ScreenUpdating = False
    Application.EnableEvents = False    ' our "Sum"/formula writes must not re-fire Worksheet_Change
    Dim dc As Boolean: dc = modState.GetDayClosed()   ' closed day = every meal renders signed
    For i = LBound(cols) To UBound(cols)
        ApplyMealBorders ws, CLng(cols(i)), ((ws.CheckBoxes(CStr(cbs(i))).Value = xlOn) Or dc)
    Next i
    ws.Calculate   ' evaluate the freshly-written Sum formulas
    Application.EnableEvents = prevEv
    Application.ScreenUpdating = prevSU
End Sub

' Parse a "dd/mm/yyyy" string to a Date.
Public Function ParseDmy(ByVal s As String) As Date
    ParseDmy = DateSerial(CInt(Right$(s, 4)), CInt(Mid$(s, 4, 2)), CInt(Left$(s, 2)))
End Function

' Create the < (prev) and > (next) day buttons over A1 and C1.
Private Sub EnsureNavButtons(ByVal ws As Worksheet)
    On Error Resume Next
    ws.Buttons("btnPrevDay").Delete
    ws.Buttons("btnNextDay").Delete
    On Error GoTo 0

    Dim a1 As Range: Set a1 = ws.Cells(1, 1)
    Dim bPrev As Button
    Set bPrev = ws.Buttons.Add(a1.Left, a1.Top, a1.Width, a1.Height)
    bPrev.name = "btnPrevDay": bPrev.Caption = "<": bPrev.OnAction = "PrevDay"

    Dim d1 As Range: Set d1 = ws.Cells(1, 4)
    Dim bNext As Button
    Set bNext = ws.Buttons.Add(d1.Left, d1.Top, d1.Width, d1.Height)
    bNext.name = "btnNextDay": bNext.Caption = ">": bNext.OnAction = "NextDay"
End Sub

Public Sub PrevDay()
    ShiftDay -1
End Sub
Public Sub NextDay()
    ShiftDay 1
End Sub

Private Sub ShiftDay(ByVal delta As Long)
    On Error GoTo CleanUp
    Dim dRow As Long, dCol As Long
    dRow = CLng(modSettings.GetSettingsDict("Date Row"))
    dCol = CLng(modSettings.GetSettingsDict("Date Column"))
    Dim d As Date
    d = ParseDmy(CStr(Worksheets("Plan").Cells(dRow, dCol).Value)) + delta
    modState.SetDateChangeFromCode True
    Worksheets("Plan").Cells(dRow, dCol).Value = Format$(d, "dd/mm/yyyy")
    modState.SetDateChangeFromCode False
    modDay.GetDay
CleanUp:
    modError.ReportError "modPlan.ShiftDay"
End Sub

' Show < only if a prior day exists (> Start Date); > only if a later day exists
' (< the open day, whose date GetOpenDay stores in Settings!F1).
Public Sub UpdateNavButtons()
    On Error Resume Next
    Dim ws As Worksheet: Set ws = Worksheets("Plan")
    Dim dRow As Long, dCol As Long
    dRow = CLng(modSettings.GetSettingsDict("Date Row"))
    dCol = CLng(modSettings.GetSettingsDict("Date Column"))
    Dim shown As Date: shown = ParseDmy(CStr(ws.Cells(dRow, dCol).Value))

    ' "<" available while we're past the Start Date (col B of the Start Date row).
    Dim startS As String: startS = modSettings.GetSettingsDict("Start Date")
    Dim hasPrev As Boolean: hasPrev = True
    If startS <> "" Then hasPrev = (shown > ParseDmy(startS))

    ' ">" available only when the shown day is closed (a later day exists); the
    ' open/latest day is the only one not closed.
    ws.Buttons("btnPrevDay").Visible = hasPrev
    ws.Buttons("btnNextDay").Visible = modState.GetDayClosed()
End Sub

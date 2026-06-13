Attribute VB_Name = "modUI"
Option Explicit

'Prevents re-entry
Public gSignBusy As Boolean

'Set a Forms checkbox value without letting its OnAction macro run
Private Sub SetCheckboxValueSilently(ByVal ws As Worksheet, ByVal cbName As String, ByVal newValue As Long)
    Dim cb As CheckBox
    Dim oldAction As String

    Set cb = ws.CheckBoxes(cbName)
    oldAction = cb.OnAction

    On Error Resume Next
    cb.OnAction = ""          'block any assigned macro
    On Error GoTo 0

    cb.Value = newValue

    On Error Resume Next
    cb.OnAction = oldAction   'restore
    On Error GoTo 0
End Sub

'Make group checkboxes non interactive: no macro + disabled
Public Sub EnsureGroupCheckboxesNonInteractive()
    Dim ws As Worksheet
    Set ws = Worksheets("Plan")

    On Error Resume Next
    ws.CheckBoxes("cbSignF").OnAction = ""
    ws.CheckBoxes("cbSignN").OnAction = ""
    ws.CheckBoxes("cbSignV").OnAction = ""

    ws.CheckBoxes("cbSignF").Enabled = False
    ws.CheckBoxes("cbSignN").Enabled = False
    ws.CheckBoxes("cbSignV").Enabled = False
    On Error GoTo 0
End Sub

Public Sub SignCheckbox_Click()
    On Error GoTo CleanUp

    Dim cbName As String
    Dim ws As Worksheet
    Dim cb As CheckBox

    cbName = Application.Caller

    If gSignBusy Then
        Exit Sub
    End If
    gSignBusy = True

    Set ws = Worksheets("Plan")
    Set cb = ws.CheckBoxes(cbName)

    'Group checkboxes must never be user clickable
    If cbName = "cbSignF" Or cbName = "cbSignN" Or cbName = "cbSignV" Then
        GoTo CleanUp
    End If

    'Hard debounce: if same checkbox fires again quickly, restore last processed value and exit
    Static lastTime As Object
    Static lastValue As Object
    If lastTime Is Nothing Then Set lastTime = CreateObject("Scripting.Dictionary")
    If lastValue Is Nothing Then Set lastValue = CreateObject("Scripting.Dictionary")

    Dim nowT As Double, dt As Double
    nowT = Timer

    If lastTime.Exists(cbName) Then
        dt = nowT - CDbl(lastTime(cbName))
        If dt < 0 Then dt = dt + 86400

        If dt < 0.45 Then
            If lastValue.Exists(cbName) Then cb.Value = CLng(lastValue(cbName))
            GoTo CleanUp
        End If
    End If

    lastTime(cbName) = nowT
    lastValue(cbName) = CLng(cb.Value)

    Dim v As Long
    v = cb.Value

    Dim mealRow As Long
    Dim mealColumn As Long
    Dim existence As GroupExistence

    Select Case cbName
        Case "cbSign1"
            mealRow = CLng(modSettings.GetSettingsDict("Meal 1 Row"))
            mealColumn = CLng(modSettings.GetSettingsDict("Meal 1 Column"))

        Case "cbSign2"
            mealRow = CLng(modSettings.GetSettingsDict("Meal 2 Row"))
            mealColumn = CLng(modSettings.GetSettingsDict("Meal 2 Column"))

        Case "cbSign3"
            mealRow = CLng(modSettings.GetSettingsDict("Meal 3 Row"))
            mealColumn = CLng(modSettings.GetSettingsDict("Meal 3 Column"))

        Case "cbSign4"
            mealRow = CLng(modSettings.GetSettingsDict("Meal 4 Row"))
            mealColumn = CLng(modSettings.GetSettingsDict("Meal 4 Column"))

        Case "cbSign5"
            mealRow = CLng(modSettings.GetSettingsDict("Meal 5 Row"))
            mealColumn = CLng(modSettings.GetSettingsDict("Meal 5 Column"))

        Case Else
            GoTo CleanUp
    End Select

    'Always keep group checkboxes non interactive
    EnsureGroupCheckboxesNonInteractive

    If v = xlOn Then
        existence = modDay.GetGroupExistence(mealRow, mealColumn)

        If existence.Fruits Then
            If ws.CheckBoxes("cbSignF").Value = xlOff Then SetCheckboxValueSilently ws, "cbSignF", xlOn
        End If

        If existence.Nuts Then
            If ws.CheckBoxes("cbSignN").Value = xlOff Then SetCheckboxValueSilently ws, "cbSignN", xlOn
        End If

        If existence.Vegetables Then
            If ws.CheckBoxes("cbSignV").Value = xlOff Then SetCheckboxValueSilently ws, "cbSignV", xlOn
        End If

    Else
        Dim r As Long, c As Long
        Dim ex As GroupExistence
        Dim anyF As Boolean, anyN As Boolean, anyV As Boolean
        Dim mealCb As CheckBox
        Dim mealCbName As Variant

        anyF = False: anyN = False: anyV = False

        For Each mealCbName In Array("cbSign1", "cbSign2", "cbSign3", "cbSign4", "cbSign5")
            Set mealCb = ws.CheckBoxes(CStr(mealCbName))

            If mealCb.Value = xlOn Then
                Select Case CStr(mealCbName)
                    Case "cbSign1"
                        r = CLng(modSettings.GetSettingsDict("Meal 1 Row"))
                        c = CLng(modSettings.GetSettingsDict("Meal 1 Column"))
                    Case "cbSign2"
                        r = CLng(modSettings.GetSettingsDict("Meal 2 Row"))
                        c = CLng(modSettings.GetSettingsDict("Meal 2 Column"))
                    Case "cbSign3"
                        r = CLng(modSettings.GetSettingsDict("Meal 3 Row"))
                        c = CLng(modSettings.GetSettingsDict("Meal 3 Column"))
                    Case "cbSign4"
                        r = CLng(modSettings.GetSettingsDict("Meal 4 Row"))
                        c = CLng(modSettings.GetSettingsDict("Meal 4 Column"))
                    Case "cbSign5"
                        r = CLng(modSettings.GetSettingsDict("Meal 5 Row"))
                        c = CLng(modSettings.GetSettingsDict("Meal 5 Column"))
                End Select

                ex = modDay.GetGroupExistence(r, c)
                If ex.Fruits Then anyF = True
                If ex.Nuts Then anyN = True
                If ex.Vegetables Then anyV = True
            End If
        Next mealCbName

        If Not anyF Then
            SetCheckboxValueSilently ws, "cbSignF", xlOff
        End If
        If Not anyN Then
            SetCheckboxValueSilently ws, "cbSignN", xlOff
        End If
        If Not anyV Then
            SetCheckboxValueSilently ws, "cbSignV", xlOff
        End If
    End If

    modUI.ApplyDayLocksFromCheckboxes modState.GetDayClosed()
    modPlan.ApplyAllMealBorders

CleanUp:
    modError.ReportError "modUI.SignCheckbox_Click"
    gSignBusy = False
End Sub

Public Sub EndDay()
    On Error GoTo CleanUp
    Application.ScreenUpdating = False

    Dim result As String
    result = modApi.Execute( _
        modApi.methodPost, _
        "days/" & modDateUtils.DateStrToParam(CStr(Worksheets("Plan").Cells(1, 2).Value)) & "/end" _
    )

    MsgBox result
    modDay.GetOpenDay

CleanUp:
    modError.ReportError "modUI.EndDay"
    Application.ScreenUpdating = True
End Sub

Public Sub RevertDay()
    On Error GoTo CleanUp
    Application.ScreenUpdating = False

    Dim result As String
    result = modApi.Execute( _
        modApi.methodPost, _
        "days/" & modDateUtils.DateStrToParam(CStr(Worksheets("Plan").Cells(1, 2).Value)) & "/revert" _
    )

    MsgBox result
    modDay.GetOpenDay

CleanUp:
    modError.ReportError "modUI.RevertDay"
    Application.ScreenUpdating = True
End Sub

Public Sub EnsurePlanProtection()
    Dim ws As Worksheet
    Set ws = Worksheets("Plan")

    On Error Resume Next
    ws.Unprotect Password:=""
    On Error GoTo 0

    ws.Protect Password:="", UserInterfaceOnly:=True

    'Always enforce group checkbox behavior
    EnsureGroupCheckboxesNonInteractive
End Sub

Public Sub SetMealTableEditable(ByVal mealRow As Long, ByVal mealCol As Long, ByVal editable As Boolean)
    Dim ws As Worksheet
    Dim addRow As Long, nameCol As Long, weightCol As Long
    Dim size As Long

    Set ws = Worksheets("Plan")

    addRow = CLng(modSettings.GetSettingsDict("Meals Addition Row"))
    nameCol = CLng(modSettings.GetSettingsDict("Meals Addition Name Column"))
    weightCol = CLng(modSettings.GetSettingsDict("Meals Addition Weight Column"))
    size = modPlan.ITEM_CAPACITY   ' editable item rows (no fixed meal size)

    Dim rng As Range
    Set rng = Union( _
        ws.Range(ws.Cells(mealRow + addRow, mealCol + nameCol), ws.Cells(mealRow + addRow + size - 1, mealCol + nameCol)), _
        ws.Range(ws.Cells(mealRow + addRow, mealCol + weightCol), ws.Cells(mealRow + addRow + size - 1, mealCol + weightCol)) _
    )

    rng.Locked = Not editable
End Sub

Public Sub SetDayWeightEditable(ByVal editable As Boolean)
    Dim ws As Worksheet
    Dim r As Long, c As Long

    Set ws = Worksheets("Plan")
    r = CLng(modSettings.GetSettingsDict("Person Weight Row"))
    c = CLng(modSettings.GetSettingsDict("Person Weight Column"))

    ws.Cells(r, c).Locked = Not editable
End Sub

Public Sub ApplyDayLocksFromCheckboxes(ByVal dayClosed As Boolean)
    Dim ws As Worksheet
    Set ws = Worksheets("Plan")

    On Error Resume Next
    ws.Unprotect Password:=""
    On Error GoTo 0

    ws.Cells.Locked = True

    UnlockDayNavigationAreas
    UnlockDailyOutputs

    EnsureGroupCheckboxesNonInteractive

    If dayClosed Then
        SetDayWeightEditable False

        SetMealTableEditable CLng(modSettings.GetSettingsDict("Meal 1 Row")), CLng(modSettings.GetSettingsDict("Meal 1 Column")), False
        SetMealTableEditable CLng(modSettings.GetSettingsDict("Meal 2 Row")), CLng(modSettings.GetSettingsDict("Meal 2 Column")), False
        SetMealTableEditable CLng(modSettings.GetSettingsDict("Meal 3 Row")), CLng(modSettings.GetSettingsDict("Meal 3 Column")), False
        SetMealTableEditable CLng(modSettings.GetSettingsDict("Meal 4 Row")), CLng(modSettings.GetSettingsDict("Meal 4 Column")), False
        SetMealTableEditable CLng(modSettings.GetSettingsDict("Meal 5 Row")), CLng(modSettings.GetSettingsDict("Meal 5 Column")), False
        SetMealTableEditable CLng(modSettings.GetSettingsDict("Fruits Row")), CLng(modSettings.GetSettingsDict("Fruits Column")), False
        SetMealTableEditable CLng(modSettings.GetSettingsDict("Nuts Row")), CLng(modSettings.GetSettingsDict("Nuts Column")), False
        SetMealTableEditable CLng(modSettings.GetSettingsDict("Vegetables Row")), CLng(modSettings.GetSettingsDict("Vegetables Column")), False
    Else
        SetDayWeightEditable True

        SetMealTableEditable CLng(modSettings.GetSettingsDict("Meal 1 Row")), CLng(modSettings.GetSettingsDict("Meal 1 Column")), (ws.CheckBoxes("cbSign1").Value = xlOff)
        SetMealTableEditable CLng(modSettings.GetSettingsDict("Meal 2 Row")), CLng(modSettings.GetSettingsDict("Meal 2 Column")), (ws.CheckBoxes("cbSign2").Value = xlOff)
        SetMealTableEditable CLng(modSettings.GetSettingsDict("Meal 3 Row")), CLng(modSettings.GetSettingsDict("Meal 3 Column")), (ws.CheckBoxes("cbSign3").Value = xlOff)
        SetMealTableEditable CLng(modSettings.GetSettingsDict("Meal 4 Row")), CLng(modSettings.GetSettingsDict("Meal 4 Column")), (ws.CheckBoxes("cbSign4").Value = xlOff)
        SetMealTableEditable CLng(modSettings.GetSettingsDict("Meal 5 Row")), CLng(modSettings.GetSettingsDict("Meal 5 Column")), (ws.CheckBoxes("cbSign5").Value = xlOff)
        SetMealTableEditable CLng(modSettings.GetSettingsDict("Fruits Row")), CLng(modSettings.GetSettingsDict("Fruits Column")), (ws.CheckBoxes("cbSignF").Value = xlOff)
        SetMealTableEditable CLng(modSettings.GetSettingsDict("Nuts Row")), CLng(modSettings.GetSettingsDict("Nuts Column")), (ws.CheckBoxes("cbSignN").Value = xlOff)
        SetMealTableEditable CLng(modSettings.GetSettingsDict("Vegetables Row")), CLng(modSettings.GetSettingsDict("Vegetables Column")), (ws.CheckBoxes("cbSignV").Value = xlOff)
    End If

    ws.Protect Password:="", UserInterfaceOnly:=True
End Sub

Public Sub UnlockDayNavigationAreas()
    Dim ws As Worksheet
    Set ws = Worksheets("Plan")

    ' date cell (B1) editable so the user can type a date to jump days
    With ws.Range("B1")
        If .MergeCells Then
            .MergeArea.Locked = False
        Else
            .Locked = False
        End If
    End With
End Sub

Public Sub UnlockDailyOutputs()
    Dim ws As Worksheet
    Set ws = Worksheets("Plan")

    ' Daily/Needed/Left are computed formulas now -> they stay locked (read-only).
    ' Only the command/scratch cells need to be writable.
    ws.Range("A30").Locked = False
    ws.Range("A21:B25").Locked = False
End Sub

Public Sub RefreshGroupSignsFromMeals()
    Dim ws As Worksheet
    Set ws = Worksheets("Plan")

    EnsureGroupCheckboxesNonInteractive

    Dim anyF As Boolean, anyN As Boolean, anyV As Boolean
    Dim mealCbName As Variant
    Dim r As Long, c As Long
    Dim ex As GroupExistence

    anyF = False: anyN = False: anyV = False

    For Each mealCbName In Array("cbSign1", "cbSign2", "cbSign3", "cbSign4", "cbSign5")
        If ws.CheckBoxes(CStr(mealCbName)).Value = xlOn Then
            Select Case CStr(mealCbName)
                Case "cbSign1"
                    r = CLng(modSettings.GetSettingsDict("Meal 1 Row"))
                    c = CLng(modSettings.GetSettingsDict("Meal 1 Column"))
                Case "cbSign2"
                    r = CLng(modSettings.GetSettingsDict("Meal 2 Row"))
                    c = CLng(modSettings.GetSettingsDict("Meal 2 Column"))
                Case "cbSign3"
                    r = CLng(modSettings.GetSettingsDict("Meal 3 Row"))
                    c = CLng(modSettings.GetSettingsDict("Meal 3 Column"))
                Case "cbSign4"
                    r = CLng(modSettings.GetSettingsDict("Meal 4 Row"))
                    c = CLng(modSettings.GetSettingsDict("Meal 4 Column"))
                Case "cbSign5"
                    r = CLng(modSettings.GetSettingsDict("Meal 5 Row"))
                    c = CLng(modSettings.GetSettingsDict("Meal 5 Column"))
            End Select

            ex = modDay.GetGroupExistence(r, c)
            If ex.Fruits Then anyF = True
            If ex.Nuts Then anyN = True
            If ex.Vegetables Then anyV = True
        End If
    Next mealCbName

    SetCheckboxValueSilently ws, "cbSignF", IIf(anyF, xlOn, xlOff)
    SetCheckboxValueSilently ws, "cbSignN", IIf(anyN, xlOn, xlOff)
    SetCheckboxValueSilently ws, "cbSignV", IIf(anyV, xlOn, xlOff)
End Sub



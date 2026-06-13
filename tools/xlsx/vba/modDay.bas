Attribute VB_Name = "modDay"
Option Explicit

Public Sub GetDay()
    On Error GoTo CleanUp
    Application.ScreenUpdating = False

    Dim result As String
    Dim json As Dictionary

    result = modApi.Execute( _
        modApi.methodGet, _
        "days/" & modDateUtils.DateStrToParam(CStr(Worksheets("Plan").Cells(1, 2).Value)) _
    )

    Set json = JsonConverter.ParseJson(result)

    If json("status") = "SUCCESS" Then
        SetDayValues json
    Else
        MsgBox json("errorMessage")
    End If

CleanUp:
    modError.ReportError "modDay.GetDay"
    Application.ScreenUpdating = True
End Sub

Public Sub SetDay()
    If Not modState.GetDayClosed Then
        Dim payload As String
        Dim result As String
    
        Dim pwRow As Long, pwCol As Long
        pwRow = CLng(modSettings.GetSettingsDict("Person Weight Row"))
        pwCol = CLng(modSettings.GetSettingsDict("Person Weight Column"))

        payload = "{" & GetMealsJson()
        payload = payload & ", ""weight"": " & CStr(Worksheets("Plan").Cells(pwRow, pwCol).Value)
        payload = payload & "}"

        result = modApi.Execute( _
            modApi.methodPatch, _
            "days/" & modDateUtils.DateStrToParam(CStr(Worksheets("Plan").Cells(1, 2).Value)), _
            payload _
        )
    
        MsgBox result
    Else
        MsgBox "Day Closed"
    End If
End Sub

Public Sub GetOpenDay()
    Dim result As String
    Dim json As Dictionary

    result = modApi.Execute(modApi.methodGet, "days/open")
    Set json = JsonConverter.ParseJson(result)

    If json("status") = "SUCCESS" Then
        modState.SetDateChangeFromCode True
        Worksheets("Plan").Cells(1, 2).Value = modDateUtils.DateParamToStr(json("day")("date"))
        modState.SetDateChangeFromCode False

        SetDayValues json
    Else
        MsgBox json("errorMessage")
    End If
End Sub

Public Sub DayChanged()
    On Error GoTo CleanUp
    Application.ScreenUpdating = False

    GetDay

CleanUp:
    modError.ReportError "modDay.DayChanged"
    Application.ScreenUpdating = True
End Sub

Private Function GetMealJson(ByVal row As Long, ByVal col As Long, ByVal cbName As String) As String
    Dim i As Long
    Dim mealsAdditionNameColumn As Long
    Dim mealsAdditionWeightColumn As Long
    Dim mealsAdditionProteinColumn As Long
    Dim mealsAdditionFatColumn As Long
    Dim mealsAdditionCaloriesColumn As Long
    Dim mealsAdditionRow As Long
    Dim jsonFoods As String
    Dim mealClosed As String ' "true"/"false" for JSON

    mealsAdditionNameColumn = CLng(modSettings.GetSettingsDict("Meals Addition Name Column"))
    mealsAdditionWeightColumn = CLng(modSettings.GetSettingsDict("Meals Addition Weight Column"))
    mealsAdditionProteinColumn = CLng(modSettings.GetSettingsDict("Meals Addition Protein Column"))
    mealsAdditionFatColumn = CLng(modSettings.GetSettingsDict("Meals Addition Fat Column"))
    mealsAdditionCaloriesColumn = CLng(modSettings.GetSettingsDict("Meals Addition Calories Column"))
    mealsAdditionRow = CLng(modSettings.GetSettingsDict("Meals Addition Row"))

    mealClosed = IIf(Worksheets("Plan").CheckBoxes(cbName).Value = xlOn, "true", "false")

    i = 0
    jsonFoods = ""
    Dim added As Long: added = 0
    Dim nmV As String, wV As String

    ' Scan while the row has a name OR a weight (stops at the blank open row / Sum).
    ' Only named rows are saved; a weight-only row is kept on screen but skipped here.
    Do
        nmV = CStr(Worksheets("Plan").Cells(row + mealsAdditionRow + i, col + mealsAdditionNameColumn).Value)
        wV = CStr(Worksheets("Plan").Cells(row + mealsAdditionRow + i, col + mealsAdditionWeightColumn).Value)
        If nmV = "Sum" Then Exit Do
        If nmV = "" And wV = "" Then Exit Do

        If nmV <> "" Then
            If added > 0 Then jsonFoods = jsonFoods & ", "
            jsonFoods = jsonFoods & "{"
            jsonFoods = jsonFoods & """name"":""" & nmV & """"
            jsonFoods = jsonFoods & ", ""weight"":""" & wV & """"
            jsonFoods = jsonFoods & ", ""protein"":""" & CStr(Worksheets("Plan").Cells(row + mealsAdditionRow + i, col + mealsAdditionProteinColumn).Value) & """"
            jsonFoods = jsonFoods & ", ""fat"":""" & CStr(Worksheets("Plan").Cells(row + mealsAdditionRow + i, col + mealsAdditionFatColumn).Value) & """"
            jsonFoods = jsonFoods & ", ""calories"":""" & CStr(Worksheets("Plan").Cells(row + mealsAdditionRow + i, col + mealsAdditionCaloriesColumn).Value) & """"
            jsonFoods = jsonFoods & "}"
            added = added + 1
        End If

        i = i + 1
    Loop

    GetMealJson = "{""name"": """ & CStr(Worksheets("Plan").Cells(row, col).Value) & """, ""meal_closed"": " & mealClosed & ", ""foods"": [" & jsonFoods & "]}"
End Function

Private Function GetMealsJson() As String
    Dim meal1Row As Long, meal1Column As Long
    Dim meal2Row As Long, meal2Column As Long
    Dim meal3Row As Long, meal3Column As Long
    Dim meal4Row As Long, meal4Column As Long
    Dim meal5Row As Long, meal5Column As Long
    Dim fruitsRow As Long, fruitsColumn As Long
    Dim nutsRow As Long, nutsColumn As Long
    Dim vegetablesRow As Long, vegetablesColumn As Long
    Dim json As String

    meal1Row = CLng(modSettings.GetSettingsDict("Meal 1 Row"))
    meal1Column = CLng(modSettings.GetSettingsDict("Meal 1 Column"))
    meal2Row = CLng(modSettings.GetSettingsDict("Meal 2 Row"))
    meal2Column = CLng(modSettings.GetSettingsDict("Meal 2 Column"))
    meal3Row = CLng(modSettings.GetSettingsDict("Meal 3 Row"))
    meal3Column = CLng(modSettings.GetSettingsDict("Meal 3 Column"))
    meal4Row = CLng(modSettings.GetSettingsDict("Meal 4 Row"))
    meal4Column = CLng(modSettings.GetSettingsDict("Meal 4 Column"))
    meal5Row = CLng(modSettings.GetSettingsDict("Meal 5 Row"))
    meal5Column = CLng(modSettings.GetSettingsDict("Meal 5 Column"))
    fruitsRow = CLng(modSettings.GetSettingsDict("Fruits Row"))
    fruitsColumn = CLng(modSettings.GetSettingsDict("Fruits Column"))
    nutsRow = CLng(modSettings.GetSettingsDict("Nuts Row"))
    nutsColumn = CLng(modSettings.GetSettingsDict("Nuts Column"))
    vegetablesRow = CLng(modSettings.GetSettingsDict("Vegetables Row"))
    vegetablesColumn = CLng(modSettings.GetSettingsDict("Vegetables Column"))

    json = """meals"": ["
    json = json & GetMealJson(meal1Row, meal1Column, "cbSign1")
    json = json & ", " & GetMealJson(meal2Row, meal2Column, "cbSign2")
    json = json & ", " & GetMealJson(meal3Row, meal3Column, "cbSign3")
    json = json & ", " & GetMealJson(meal4Row, meal4Column, "cbSign4")
    json = json & ", " & GetMealJson(meal5Row, meal5Column, "cbSign5")
    json = json & ", " & GetMealJson(fruitsRow, fruitsColumn, "cbSignF")
    json = json & ", " & GetMealJson(nutsRow, nutsColumn, "cbSignN")
    json = json & ", " & GetMealJson(vegetablesRow, vegetablesColumn, "cbSignV")
    json = json & "]"

    GetMealsJson = json
End Function

Private Sub SetDayValues(ByVal json As Dictionary)
    On Error GoTo CleanUp
    Application.EnableEvents = False
    
    Worksheets("Plan").Unprotect Password:=""
    
    Dim i As Long
    Dim mealRow As Long
    Dim mealColumn As Long

    Dim mealsAdditionRow As Long
    Dim mealsAdditionNameColumn As Long
    Dim mealsAdditionWeightColumn As Long
    Dim mealsAdditionProteinColumn As Long
    Dim mealsAdditionFatColumn As Long
    Dim mealsAdditionCaloriesColumn As Long
    Dim mealsSize As Long

    Dim mealCBValue As Long

    Dim mealName As String
    Dim currentCbName As String
    Dim dayStr As String
    Dim dayStrDate As Date

    Dim dayClosed As Boolean
    Dim mealClosed As Boolean

    Dim meal As Variant
    Dim food As Variant

    mealsAdditionRow = CLng(modSettings.GetSettingsDict("Meals Addition Row"))
    mealsAdditionNameColumn = CLng(modSettings.GetSettingsDict("Meals Addition Name Column"))
    mealsAdditionWeightColumn = CLng(modSettings.GetSettingsDict("Meals Addition Weight Column"))
    mealsAdditionProteinColumn = CLng(modSettings.GetSettingsDict("Meals Addition Protein Column"))
    mealsAdditionFatColumn = CLng(modSettings.GetSettingsDict("Meals Addition Fat Column"))
    mealsAdditionCaloriesColumn = CLng(modSettings.GetSettingsDict("Meals Addition Calories Column"))
    mealsSize = modPlan.ITEM_CAPACITY   ' clear/scan headroom (no fixed meal size)

    Worksheets("Plan").Cells( _
        CLng(modSettings.GetSettingsDict("Person Weight Row")), _
        CLng(modSettings.GetSettingsDict("Person Weight Column")) _
    ).Value = json("day")("weight")

    dayClosed = CBool(json("day")("day_closed"))
    
    SetDayClosedStatusColor dayClosed
    
    modState.SetDayClosed dayClosed

    Worksheets("Plan").CheckBoxes("cbSign1").Visible = Not dayClosed
    Worksheets("Plan").CheckBoxes("cbSign2").Visible = Not dayClosed
    Worksheets("Plan").CheckBoxes("cbSign3").Visible = Not dayClosed
    Worksheets("Plan").CheckBoxes("cbSign4").Visible = Not dayClosed
    Worksheets("Plan").CheckBoxes("cbSign5").Visible = Not dayClosed
    Worksheets("Plan").CheckBoxes("cbSignF").Visible = Not dayClosed
    Worksheets("Plan").CheckBoxes("cbSignN").Visible = Not dayClosed
    Worksheets("Plan").CheckBoxes("cbSignV").Visible = Not dayClosed

    For Each meal In json("day")("meals")
        mealName = CStr(meal("name"))

        mealRow = CLng(modSettings.GetSettingsDict(mealName & " Row"))
        mealColumn = CLng(modSettings.GetSettingsDict(mealName & " Column"))

        If Not dayClosed Then
            mealClosed = CBool(meal("meal_closed"))
            mealCBValue = IIf(mealClosed, xlOn, xlOff)

            Select Case mealName
                Case "Meal 1": currentCbName = "cbSign1"
                Case "Meal 2": currentCbName = "cbSign2"
                Case "Meal 3": currentCbName = "cbSign3"
                Case "Meal 4": currentCbName = "cbSign4"
                Case "Meal 5": currentCbName = "cbSign5"
                Case "Fruits": currentCbName = "cbSignF"
                Case "Nuts": currentCbName = "cbSignN"
                Case "Vegetables": currentCbName = "cbSignV"
                Case Else: currentCbName = vbNullString
            End Select

            If currentCbName <> vbNullString Then
                Worksheets("Plan").CheckBoxes(currentCbName).Value = mealCBValue
            End If
        End If

        i = 0

        For Each food In meal("foods")
            Worksheets("Plan").Cells(mealRow + mealsAdditionRow + i, mealColumn + mealsAdditionNameColumn).Value = food("name")
            Worksheets("Plan").Cells(mealRow + mealsAdditionRow + i, mealColumn + mealsAdditionWeightColumn).Value = food("weight")

            If dayClosed Then
                Worksheets("Plan").Cells(mealRow + mealsAdditionRow + i, mealColumn + mealsAdditionProteinColumn).Value = food("protein")
                Worksheets("Plan").Cells(mealRow + mealsAdditionRow + i, mealColumn + mealsAdditionFatColumn).Value = food("fat")
                Worksheets("Plan").Cells(mealRow + mealsAdditionRow + i, mealColumn + mealsAdditionCaloriesColumn).Value = food("calories")
            End If

            i = i + 1
        Next food

        Do While i < mealsSize
            Worksheets("Plan").Cells(mealRow + mealsAdditionRow + i, mealColumn + mealsAdditionCaloriesColumn).Value = ""
            Worksheets("Plan").Cells(mealRow + mealsAdditionRow + i, mealColumn + mealsAdditionFatColumn).Value = ""
            Worksheets("Plan").Cells(mealRow + mealsAdditionRow + i, mealColumn + mealsAdditionProteinColumn).Value = ""
            Worksheets("Plan").Cells(mealRow + mealsAdditionRow + i, mealColumn + mealsAdditionNameColumn).Value = ""
            Worksheets("Plan").Cells(mealRow + mealsAdditionRow + i, mealColumn + mealsAdditionWeightColumn).Value = ""
            i = i + 1
        Loop
    Next meal
    
    If Not dayClosed Then
        modPlanHelpers.RebuildAllFoodFormulas
    End If
    
    modUI.RefreshGroupSignsFromMeals
    modUI.ApplyDayLocksFromCheckboxes dayClosed

    If dayClosed Then
        Worksheets("Plan").Buttons("btnEnd").Visible = False
        Worksheets("Plan").Buttons("btnRevert").Visible = False
    Else
        dayStr = CStr(Worksheets("Plan").Cells(1, 2).Value)
        dayStrDate = DateSerial(CInt(Right$(dayStr, 4)), CInt(Mid$(dayStr, 4, 2)), CInt(Left$(dayStr, 2)))

        If dayStrDate = Date Then
            Worksheets("Plan").Buttons("btnEnd").Visible = True
            Worksheets("Plan").Buttons("btnRevert").Visible = False
        Else
            Worksheets("Plan").Buttons("btnEnd").Visible = False
            Worksheets("Plan").Buttons("btnRevert").Visible = True
        End If
    End If

    modPlan.UpdateNavButtons
    modPlan.ApplyAllMealBorders

CleanUp:
    modError.ReportError "modDay.SetDayValues"
    Application.EnableEvents = True
End Sub

Public Function GetGroupExistence(ByVal row As Long, ByVal col As Long) As GroupExistence
    Dim i As Long
    Dim mealsAdditionRow As Long
    Dim mealsAdditionNameColumn As Long
    Dim mealsAdditionWeightColumn As Long

    Dim mealFoodName As String, wV As String
    Dim existence As GroupExistence

    mealsAdditionRow = CLng(modSettings.GetSettingsDict("Meals Addition Row"))
    mealsAdditionNameColumn = CLng(modSettings.GetSettingsDict("Meals Addition Name Column"))
    mealsAdditionWeightColumn = CLng(modSettings.GetSettingsDict("Meals Addition Weight Column"))

    i = 0

    Do
        mealFoodName = CStr(Worksheets("Plan").Cells(row + mealsAdditionRow + i, col + mealsAdditionNameColumn).Value)
        wV = CStr(Worksheets("Plan").Cells(row + mealsAdditionRow + i, col + mealsAdditionWeightColumn).Value)
        If mealFoodName = "Sum" Then Exit Do
        If mealFoodName = "" And wV = "" Then Exit Do

        Select Case mealFoodName
            Case "Fruits": existence.Fruits = True
            Case "Nuts": existence.Nuts = True
            Case "Vegetables": existence.Vegetables = True
        End Select

        i = i + 1
    Loop

    GetGroupExistence = existence
End Function

Public Sub SetDayClosedStatusColor(ByVal dayClosed As Boolean)
    Dim ws As Worksheet
    Dim r As Long, c As Long
    Dim wasProtected As Boolean

    Set ws = Worksheets("Plan")
    r = CLng(modSettings.GetSettingsDict("Day Closed Status Row"))
    c = CLng(modSettings.GetSettingsDict("Day Closed Status Column"))

    wasProtected = ws.ProtectContents

    If wasProtected Then ws.Unprotect Password:=""

    'Temporarily unlock so we can change formatting safely
    ws.Cells(r, c).Locked = False

    ' Colour D1 only for the open day (then > is hidden); on closed days clear the
    ' fill so the > button shows on a plain cell.
    With ws.Cells(r, c).Interior
        If Not dayClosed Then
            .Pattern = xlSolid
            .Color = RGB(46, 242, 168)
        Else
            .Pattern = xlNone
        End If
    End With

    'Lock back
    ws.Cells(r, c).Locked = True

    If wasProtected Then ws.Protect Password:="", UserInterfaceOnly:=True
End Sub



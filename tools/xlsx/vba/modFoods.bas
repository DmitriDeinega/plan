Attribute VB_Name = "modFoods"
Option Explicit

Public Sub GetFoods()
    On Error GoTo CleanUp
    Application.EnableEvents = False

    Dim column As Long
    Dim row As Long

    Dim foodsProteinColumn As Long
    Dim foodsFatColumn As Long
    Dim foodsCarbColumn As Long

    Dim foodProteinRow As Long
    Dim foodsFatRow As Long
    Dim foodsCarbRow As Long

    Dim foodsAdditionNameColumn As Long
    Dim foodsAdditionProteinColumn As Long
    Dim foodsAdditionFatColumn As Long
    Dim foodsAdditionCaloriesColumn As Long
    Dim foodsAdditionInnerTypeColumn As Long
    Dim foodsAdditionAvailableColumn As Long

    Dim result As String
    Dim json As Dictionary
    Dim food As Variant

    result = modApi.Execute(modApi.methodGet, "foods")
    Set json = JsonConverter.ParseJson(result)

    If json("status") <> "SUCCESS" Then
        MsgBox "Error GetFoods: " & result
        GoTo CleanUp
    End If

    foodsProteinColumn = CLng(modSettings.GetSettingsDict("Foods Protein Column"))
    foodsFatColumn = CLng(modSettings.GetSettingsDict("Foods Fat Column"))
    foodsCarbColumn = CLng(modSettings.GetSettingsDict("Foods Carb Column"))

    foodsAdditionNameColumn = CLng(modSettings.GetSettingsDict("Foods Addition Name Column"))
    foodsAdditionProteinColumn = CLng(modSettings.GetSettingsDict("Foods Addition Protein Column"))
    foodsAdditionFatColumn = CLng(modSettings.GetSettingsDict("Foods Addition Fat Column"))
    foodsAdditionCaloriesColumn = CLng(modSettings.GetSettingsDict("Foods Addition Calories Column"))
    foodsAdditionInnerTypeColumn = CLng(modSettings.GetSettingsDict("Foods Addition Inner Type Column"))
    foodsAdditionAvailableColumn = CLng(modSettings.GetSettingsDict("Foods Addition Available Column"))

    foodProteinRow = 1
    foodsFatRow = 1
    foodsCarbRow = 1

    For Each food In json("foods")
        Select Case CStr(food("type"))
            Case "Protein"
                column = foodsProteinColumn
                foodProteinRow = foodProteinRow + 1
                row = foodProteinRow
            Case "Fat"
                column = foodsFatColumn
                foodsFatRow = foodsFatRow + 1
                row = foodsFatRow
            Case "Carb"
                column = foodsCarbColumn
                foodsCarbRow = foodsCarbRow + 1
                row = foodsCarbRow
            Case Else
                ' ignore unknown type
                GoTo NextFood
        End Select

        Worksheets("Foods").Cells(row, column + foodsAdditionNameColumn).Value = food("name")
        Worksheets("Foods").Cells(row, column + foodsAdditionProteinColumn).Value = food("protein")
        Worksheets("Foods").Cells(row, column + foodsAdditionFatColumn).Value = food("fat")
        Worksheets("Foods").Cells(row, column + foodsAdditionCaloriesColumn).Value = food("calories")
        Worksheets("Foods").Cells(row, column + foodsAdditionInnerTypeColumn).Value = food("inner_type")
        Worksheets("Foods").Cells(row, column + foodsAdditionAvailableColumn).Value = food("available")

NextFood:
    Next food

    DeleteFoods foodProteinRow + 1, foodsProteinColumn
    DeleteFoods foodsFatRow + 1, foodsFatColumn
    DeleteFoods foodsCarbRow + 1, foodsCarbColumn

CleanUp:
    modError.ReportError "modFoods.GetFoods"
    Application.EnableEvents = True
End Sub

Public Sub SetFoods()
    On Error GoTo CleanUp
    Application.EnableEvents = False

    Dim payload As String
    Dim result As String

    Dim foodsProteinColumn As Long
    Dim foodsFatColumn As Long
    Dim foodsCarbColumn As Long

    foodsProteinColumn = CLng(modSettings.GetSettingsDict("Foods Protein Column"))
    foodsFatColumn = CLng(modSettings.GetSettingsDict("Foods Fat Column"))
    foodsCarbColumn = CLng(modSettings.GetSettingsDict("Foods Carb Column"))

    payload = "{""foods"": ["
    payload = payload & GetCategoryJson(foodsProteinColumn)
    payload = payload & ", " & GetCategoryJson(foodsFatColumn)
    payload = payload & ", " & GetCategoryJson(foodsCarbColumn)
    payload = payload & "]}"

    result = modApi.Execute(modApi.methodPut, "foods", payload)
    MsgBox result

CleanUp:
    modError.ReportError "modFoods.SetFoods"
    Application.EnableEvents = True
End Sub

Private Function GetCategoryJson(ByVal col As Long) As String
    Dim i As Long
    Dim foodType As String
    Dim json As String

    foodType = CStr(Worksheets("Foods").Cells(1, col).Value)
    i = 2
    json = ""

    Do While Not IsEmpty(Worksheets("Foods").Cells(i, col).Value)
        If i > 2 Then json = json & ", "

        json = json & "{"
        json = json & """type"": """ & foodType & """"
        json = json & ", ""name"": """ & CStr(Worksheets("Foods").Cells(i, col).Value) & """"
        json = json & ", ""protein"": """ & CStr(Worksheets("Foods").Cells(i, col + 1).Value) & """"
        json = json & ", ""fat"": """ & CStr(Worksheets("Foods").Cells(i, col + 2).Value) & """"
        json = json & ", ""calories"": """ & CStr(Worksheets("Foods").Cells(i, col + 3).Value) & """"
        json = json & ", ""inner_type"": """ & CStr(Worksheets("Foods").Cells(i, col + 4).Value) & """"
        json = json & ", ""available"": """ & CStr(Worksheets("Foods").Cells(i, col + 5).Value) & """"
        json = json & "}"

        i = i + 1
    Loop

    GetCategoryJson = json
End Function

Private Sub DeleteFoods(ByVal row As Long, ByVal column As Long)
    Dim foodsAdditionNameColumn As Long
    Dim foodsAdditionProteinColumn As Long
    Dim foodsAdditionFatColumn As Long
    Dim foodsAdditionCaloriesColumn As Long
    Dim foodsAdditionInnerTypeColumn As Long
    Dim foodsAdditionAvailableColumn As Long

    foodsAdditionNameColumn = CLng(modSettings.GetSettingsDict("Foods Addition Name Column"))
    foodsAdditionProteinColumn = CLng(modSettings.GetSettingsDict("Foods Addition Protein Column"))
    foodsAdditionFatColumn = CLng(modSettings.GetSettingsDict("Foods Addition Fat Column"))
    foodsAdditionCaloriesColumn = CLng(modSettings.GetSettingsDict("Foods Addition Calories Column"))
    foodsAdditionInnerTypeColumn = CLng(modSettings.GetSettingsDict("Foods Addition Inner Type Column"))
    foodsAdditionAvailableColumn = CLng(modSettings.GetSettingsDict("Foods Addition Available Column"))

    Do While Worksheets("Foods").Cells(row, column + foodsAdditionNameColumn).Value <> ""
        Worksheets("Foods").Cells(row, column + foodsAdditionNameColumn).Value = ""
        Worksheets("Foods").Cells(row, column + foodsAdditionProteinColumn).Value = ""
        Worksheets("Foods").Cells(row, column + foodsAdditionFatColumn).Value = ""
        Worksheets("Foods").Cells(row, column + foodsAdditionCaloriesColumn).Value = ""
        Worksheets("Foods").Cells(row, column + foodsAdditionInnerTypeColumn).Value = ""
        Worksheets("Foods").Cells(row, column + foodsAdditionAvailableColumn).Value = ""

        row = row + 1
    Loop
End Sub


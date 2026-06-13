Attribute VB_Name = "modDBSettings"
Option Explicit

' Loads everything that comes from the DB into the DBSettings sheet (separate from
' the static layout config on Settings). The Plan dashboard fetches these by name
' via INDEX/MATCH; age is computed in the dashboard formula from Birthday.
Public Sub GetDBSettings()
    On Error GoTo CleanUp
    Application.ScreenUpdating = False

    Dim result As String
    Dim json As Dictionary
    Dim Group As Variant

    result = modApi.Execute(modApi.methodGet, "settings")
    Set json = JsonConverter.ParseJson(result)

    If json("status") <> "SUCCESS" Then
        MsgBox json("errorMessage")
        GoTo CleanUp
    End If

    SetKV "Daily Protein perKg", json("settings")("daily")("protein")
    SetKV "Daily Fat perKg", json("settings")("daily")("fat")
    SetKV "Daily Calories", json("settings")("daily")("calories")
    SetKV "TDEE Multiplier", json("settings")("daily")("tdee_multiplier")
    SetKV "Calorie Type", json("settings")("daily")("calorie_type")
    SetKV "Height", json("settings")("person")("height")
    SetKV "Gender", json("settings")("person")("gender")
    SetKV "Birthday", modDateUtils.DateParamToStr(json("settings")("person")("birth_day")), True
    SetKV "Start Date", modDateUtils.DateParamToStr(json("settings")("start_date")), True

    For Each Group In json("settings")("groups")
        SetKV CStr(Group("name")) & " New Day Amount", CLng(Group("new_day_amount"))
    Next Group

    ' tidy the DBSettings sheet: fit columns to text, centre every cell
    With Worksheets(modPlan.DB_SHEET)
        .Cells.HorizontalAlignment = xlCenter
        .Cells.VerticalAlignment = xlCenter
        .Columns("A:B").AutoFit
    End With

    modSettings.ResetSettingsDict   ' refresh cache after editing settings

    Exit Sub

CleanUp:
    Dim errNum As Long, errDesc As String, errSrc As String
    errNum = Err.Number
    errDesc = Err.Description
    errSrc = Err.Source
    If errNum <> 0 Then
        MsgBox "Err " & errNum & " in " & errSrc & vbCrLf & errDesc
    End If
    Application.ScreenUpdating = True
End Sub

' Set a value by key on the DBSettings sheet (append the key if missing),
' preserving the value's native type (numbers stay numbers).
Private Sub SetKV(ByVal key As String, ByVal val As Variant, Optional ByVal asText As Boolean = False)
    Dim ws As Worksheet: Set ws = Worksheets(modPlan.DB_SHEET)
    Dim i As Long: i = 1
    Dim row As Long: row = 0
    Do While CStr(ws.Cells(i, 1).Value) <> ""
        If CStr(ws.Cells(i, 1).Value) = key Then row = i: Exit Do
        i = i + 1
    Loop
    If row = 0 Then row = i: ws.Cells(row, 1).Value = key
    ' store date-like strings as text so Excel doesn't swap dd/mm by locale
    If asText Then ws.Cells(row, 2).NumberFormat = "@"
    ws.Cells(row, 2).Value = val
End Sub

Attribute VB_Name = "modSettings"
Option Explicit

Private settingsDict As Scripting.Dictionary

' Invalidate the cached settings so the next GetSettingsDict re-reads the sheet.
' Call after programmatically changing Settings values (e.g. BuildPlanLayout).
Public Sub ResetSettingsDict()
    Set settingsDict = Nothing
End Sub

Public Function GetSettingsDict(ByVal name As String) As String
    If settingsDict Is Nothing Then
        SetSettingsDict
    End If

    If settingsDict.Exists(name) Then
        GetSettingsDict = CStr(settingsDict(name))
    Else
        GetSettingsDict = ""
    End If
End Function

Private Sub SetSettingsDict()
    Set settingsDict = New Scripting.Dictionary
    ' static layout config on Settings, then DB-loaded values on DBSettings
    LoadKeyValues "Settings"
    LoadKeyValues modPlan.DB_SHEET
End Sub

Private Sub LoadKeyValues(ByVal sheetName As String)
    Dim ws As Worksheet
    On Error Resume Next
    Set ws = Worksheets(sheetName)
    On Error GoTo 0
    If ws Is Nothing Then Exit Sub

    Dim i As Long: i = 1
    Dim k As String, v As String
    Do While ws.Cells(i, 1).Value <> ""
        k = CStr(ws.Cells(i, 1).Value)
        v = CStr(ws.Cells(i, 2).Value)
        settingsDict(k) = v
        i = i + 1
    Loop
End Sub

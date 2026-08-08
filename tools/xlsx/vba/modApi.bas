Attribute VB_Name = "modApi"
Option Explicit

Public Const methodGet As String = "GET"
Public Const methodPost As String = "POST"
Public Const methodPatch As String = "PATCH"
Public Const methodPut As String = "PUT"

' Returns TRUE when no server address is configured. The committed workbook ships with a blank
' "API Route" so it can be set per machine after copying the folder.
Public Function IsApiConfigured() As Boolean
    IsApiConfigured = (Trim$(modSettings.GetSettingsDict("API Route")) <> "")
End Function

Public Function Execute(ByVal methodType As String, ByVal endpoint As String, Optional ByVal data As String = "") As String
    Dim objHTTP As Object
    Dim methodUrl As String
    Dim apiRoute As String

    On Error GoTo Fail

    ' Central guard: with no address every caller would otherwise build a URL like
    ' "days/open" and fail deep inside MSXML. Returning a normal error response means each
    ' caller's existing status check handles it, instead of nine separate guards.
    apiRoute = Trim$(modSettings.GetSettingsDict("API Route"))
    If apiRoute = "" Then
        Execute = "{""status"":""ERROR"",""errorMessage"":""API Route is not set" & _
                  " - fill it in on the Settings sheet.""}"
        Exit Function
    End If

    Set objHTTP = CreateObject("MSXML2.ServerXMLHTTP")
    methodUrl = apiRoute & endpoint

    objHTTP.Open methodType, methodUrl, False
    objHTTP.setRequestHeader "Content-type", "application/json"
    objHTTP.send data

    Execute = objHTTP.responseText
    Exit Function

Fail:
    Execute = "{""status"":""ERROR"",""errorMessage"":""HTTP failed: " & Replace(Err.Description, """", "'") & """}"
End Function

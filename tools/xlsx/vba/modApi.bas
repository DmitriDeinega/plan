Attribute VB_Name = "modApi"
Option Explicit

Public Const methodGet As String = "GET"
Public Const methodPost As String = "POST"
Public Const methodPatch As String = "PATCH"
Public Const methodPut As String = "PUT"

Public Function Execute(ByVal methodType As String, ByVal endpoint As String, Optional ByVal data As String = "") As String
    Dim objHTTP As Object
    Dim methodUrl As String

    On Error GoTo Fail

    Set objHTTP = CreateObject("MSXML2.ServerXMLHTTP")
    methodUrl = modSettings.GetSettingsDict("API Route") & endpoint

    objHTTP.Open methodType, methodUrl, False
    objHTTP.setRequestHeader "Content-type", "application/json"
    objHTTP.send data

    Execute = objHTTP.responseText
    Exit Function

Fail:
    Execute = "{""status"":""ERROR"",""errorMessage"":""HTTP failed: " & Replace(Err.Description, """", "'") & """}"
End Function

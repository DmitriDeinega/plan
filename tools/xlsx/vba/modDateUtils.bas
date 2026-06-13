Attribute VB_Name = "modDateUtils"
Option Explicit

Public Function DateStrToParam(ByVal dateStr As String) As String
    DateStrToParam = Left$(dateStr, 2) & Mid$(dateStr, 4, 2) & Right$(dateStr, 4)
End Function

Public Function DateParamToStr(ByVal dateParam As String) As String
    DateParamToStr = Left$(dateParam, 2) & "/" & Mid$(dateParam, 3, 2) & "/" & Right$(dateParam, 4)
End Function


' launcher.vbs - Launch Node.js process hidden
Set WshShell = CreateObject("WScript.Shell")

' Arguments:
' 0: Path to Node.js executable
' 1: Path to Script (server.cjs)
' Rest: Arguments to pass to script

If WScript.Arguments.Count < 2 Then
    WScript.Echo "Usage: launcher.vbs <node_path> <script_path> [args...]"
    WScript.Quit 1
End If

NodePath = WScript.Arguments(0)
ScriptPath = WScript.Arguments(1)
Args = ""

' Collect remaining args
For i = 2 To WScript.Arguments.Count - 1
    Args = Args & " " & WScript.Arguments(i)
Next

' Run command hidden (0)
' chr(34) is double quote
Command = chr(34) & NodePath & chr(34) & " " & chr(34) & ScriptPath & chr(34) & Args

On Error Resume Next
WshShell.Run Command, 0, False

If Err.Number <> 0 Then
    WScript.Echo "Error launching application:" & vbCrLf & _
                 "Error Code: " & Hex(Err.Number) & vbCrLf & _
                 "Command: " & Command
    WScript.Quit 1
End If
On Error GoTo 0

Set WshShell = Nothing

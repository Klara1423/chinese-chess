@echo off
setlocal
set "JAVA_HOME=%LOCALAPPDATA%\Programs\Android Studio\jbr"
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [x] JDK not found. Set JAVA_HOME manually.
    exit /b 1
)
call "%~dp0gradlew.bat" %*

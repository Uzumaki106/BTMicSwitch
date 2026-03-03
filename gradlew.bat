@echo off
setlocal enabledelayedexpansion

set "DIRNAME=%~dp0"
set "CLASSPATH=%DIRNAME%gradle\wrapper\gradle-wrapper.jar"

:: Find java
if defined JAVA_HOME (
    set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) else (
    set "JAVA_EXE=java.exe"
)

echo Using Java: %JAVA_EXE%
echo Classpath: %CLASSPATH%

"%JAVA_EXE%" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*

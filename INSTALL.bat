@echo off
setlocal
cd /d "%~dp0"

echo ================================================
echo  BT Mic Switch - Complete Setup and Build
echo ================================================
echo.

:: Step 1: Set Java Home - detect automatically
echo [1/4] Detecting Java installation...
for /f "tokens=*" %%i in ('where java 2^>nul') do set JAVA_EXE=%%i
if not defined JAVA_EXE (
    echo ERROR: Java not found. Please install Java 17 from https://adoptium.net
    pause & exit /b 1
)
echo Found Java: %JAVA_EXE%

:: Get JAVA_HOME from the java.exe path
for %%i in ("%JAVA_EXE%") do set "JAVA_BIN=%%~dpi"
set "JAVA_HOME=%JAVA_BIN%.."
echo JAVA_HOME set to: %JAVA_HOME%
echo.

:: Step 2: Download gradle-wrapper.jar
echo [2/4] Downloading Gradle wrapper...
if not exist "gradle\wrapper" mkdir "gradle\wrapper"
powershell -NoProfile -Command "& { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; (New-Object Net.WebClient).DownloadFile('https://raw.githubusercontent.com/gradle/gradle/v8.11.1/gradle/wrapper/gradle-wrapper.jar', 'gradle\wrapper\gradle-wrapper.jar') }"
if not exist "gradle\wrapper\gradle-wrapper.jar" (
    echo ERROR: Failed to download gradle-wrapper.jar
    pause & exit /b 1
)
echo Done!
echo.

:: Step 3: Build
echo [3/4] Building APK (first run downloads ~200MB, takes 5-10 mins)...
set "CLASSPATH=%~dp0gradle\wrapper\gradle-wrapper.jar"
"%JAVA_HOME%\bin\java.exe" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain assembleDebug
if errorlevel 1 (
    echo.
    echo ERROR: Build failed. See output above.
    pause & exit /b 1
)

:: Step 4: Done
echo.
echo ================================================
echo  [4/4] SUCCESS! APK ready at:
echo  %~dp0app\build\outputs\apk\debug\app-debug.apk
echo ================================================
echo.
echo Transfer this APK to your phone and tap to install.
echo Or use: adb install app\build\outputs\apk\debug\app-debug.apk
echo.
pause

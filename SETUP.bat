@echo off
echo ============================================
echo  BT Mic Switch - One-Click Setup
echo ============================================
echo.

cd /d "%~dp0"

echo [1/3] Downloading Gradle wrapper jar...
if not exist "gradle\wrapper" mkdir "gradle\wrapper"
curl -L -o "gradle\wrapper\gradle-wrapper.jar" "https://github.com/gradle/gradle/raw/v8.11.1/gradle/wrapper/gradle-wrapper.jar"
if errorlevel 1 (
    echo ERROR: Could not download gradle-wrapper.jar
    echo Check your internet connection and try again.
    pause
    exit /b 1
)
echo Done!

echo.
echo [2/3] Building APK (this takes 3-5 minutes on first run)...
call gradlew.bat assembleDebug
if errorlevel 1 (
    echo ERROR: Build failed. Check output above.
    pause
    exit /b 1
)

echo.
echo [3/3] SUCCESS!
echo.
echo APK is ready at:
echo %~dp0app\build\outputs\apk\debug\app-debug.apk
echo.
echo To install on your phone:
echo  1. Connect phone via USB with USB Debugging ON
echo  2. Run:  adb install app\build\outputs\apk\debug\app-debug.apk
echo  OR transfer the APK to your phone and tap to install.
echo.
pause

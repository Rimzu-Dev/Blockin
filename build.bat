@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

set "GAME=D:\Games\Blockin"
set "CP=lib\lwjgl.jar;lib\lwjgl_util.jar"

if exist "%~dp0sources.txt" del /q "%~dp0sources.txt"

echo === Compiling Blockin (javac) ===
dir /b /s com\*.java > sources.txt
javac -encoding UTF-8 -cp "%CP%" -d "%GAME%" @sources.txt
if errorlevel 1 (
    echo [BUILD FAILED]
    exit /b 1
)

echo === Staging textures ===
xcopy /s /e /y /q "com\*.png" "%GAME%\com\" >nul
echo === Staging wavs ===
if exist "com\*.wav" xcopy /s /e /y /q "com\*.wav" "%GAME%\com\" >nul

if exist "Mods" (
    echo === Staging mods ===
    xcopy /s /e /y /q "Mods" "%GAME%\Mods\" >nul
)

if not exist "%GAME%\natives" (
    echo === Staging natives ===
    xcopy /s /e /y /q "natives" "%GAME%\natives\" >nul
)

echo [BUILD OK] classes and resources deployed to %GAME%
exit /b 0
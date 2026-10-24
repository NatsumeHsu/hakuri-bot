@echo off
setlocal
title Hakuri Bot

pushd "%~dp0"
if errorlevel 1 (
    echo [ERROR] Cannot open the project directory.
    pause
    exit /b 1
)

if not exist "start.ps1" (
    echo [ERROR] start.ps1 was not found in the project directory.
    popd
    pause
    exit /b 1
)

if not exist ".env" (
    echo [ERROR] .env was not found.
    echo Copy example.env to .env and set DISCORD_TOKEN before starting.
    popd
    pause
    exit /b 1
)

echo Starting Hakuri Bot...
echo Admin console: use SERVER_ADDRESS and SERVER_PORT from .env
echo Keep this window open. Press Ctrl+C to stop the bot.
echo.

powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0start.ps1"
set "HAKURI_EXIT_CODE=%ERRORLEVEL%"

popd
if not "%HAKURI_EXIT_CODE%"=="0" (
    echo.
    echo [ERROR] Startup failed or the bot exited with code %HAKURI_EXIT_CODE%.
    pause
)
exit /b %HAKURI_EXIT_CODE%

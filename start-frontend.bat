@echo off
setlocal

set "FRONTEND_DIR=D:\computer technology\code\Project-me\tianji\tj-portal-src"

if not exist "%FRONTEND_DIR%\package.json" (
    echo Frontend directory was not found:
    echo %FRONTEND_DIR%
    pause
    exit /b 1
)

where npm >nul 2>&1
if errorlevel 1 (
    echo npm was not found. Please install Node.js and reopen this window.
    pause
    exit /b 1
)

cd /d "%FRONTEND_DIR%"

if not exist "node_modules\.bin\vite.cmd" (
    echo Frontend dependencies are missing. Running npm install...
    call npm install
    if errorlevel 1 (
        echo npm install failed.
        pause
        exit /b 1
    )
)

echo Starting frontend at http://localhost:18082
call npm run dev

echo.
echo Frontend process stopped.
pause

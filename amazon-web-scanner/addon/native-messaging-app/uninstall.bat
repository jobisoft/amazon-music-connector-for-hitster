@echo off
:: Uninstall the amazon_web_scanner_host native messaging host for Firefox on Windows.

setlocal

set "INSTALL_DIR=%APPDATA%\Mozilla\NativeMessagingHosts\amazon_web_scanner_host_helper"
set "REG_KEY=HKCU\SOFTWARE\Mozilla\NativeMessagingHosts\amazon_web_scanner_host"

echo.
echo This will uninstall the helper app of the Firefox add-on
echo "Amazon Web Scanner".
echo.
choice /c yn /n /m "Proceed with uninstallation? [y/n] "
if errorlevel 2 (
  echo.
  echo Uninstallation cancelled.
  pause
  endlocal
  exit /b 1
)
echo.

reg delete "%REG_KEY%" /f >nul 2>&1
if exist "%INSTALL_DIR%" rmdir /s /q "%INSTALL_DIR%"

echo Helper app removed.
echo.
echo Restart Firefox to apply the changes.
echo.
pause
endlocal

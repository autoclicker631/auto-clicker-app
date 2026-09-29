@echo off
powershell -ExecutionPolicy Bypass -File "%~dp0publish_new_version.ps1" %*
pause

@echo off
title GuessTheBuild Server (Paper 26.2)
cd /d "%~dp0"
echo Starting GuessTheBuild server...
"%~dp0jdk\bin\java.exe" -Xms2G -Xmx2G -jar server.jar nogui
pause

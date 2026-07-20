@echo off
REM Rolia server launcher (Windows)
REM Requires Java 25 or newer. Adjust -Xmx to the RAM you want to give the server.

cd /d "%~dp0"

java -Xmx4G --add-modules=jdk.incubator.vector --sun-misc-unsafe-memory-access=allow -jar rolia-paperclip-26.1.2.jar --nogui

pause

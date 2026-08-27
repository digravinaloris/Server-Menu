@echo off
cd /d "%~dp0"
echo Building ServerMenu 1.1.0...
mvn clean package
if errorlevel 1 (echo BUILD FAILED & pause & exit /b 1)
echo BUILD SUCCESS
echo JAR: target\ServerMenu-1.1.0.jar
pause

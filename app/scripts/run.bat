@echo off
chcp 65001 > nul
setlocal

rem jar 与 application 目录同级，工作目录固定为本脚本所在目录
cd /d "%~dp0"

java -Dfile.encoding=UTF-8 -Xms256m -Xmx512m -jar app.jar %*

endlocal
pause

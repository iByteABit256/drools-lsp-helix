@echo off
rem drools-lsp.bat - launcher for the Drools Language Server
rem Place this script in your PATH alongside the jar, e.g.:
rem   C:\Program Files\drools-lsp\drools-lsp.bat
rem   C:\Program Files\drools-lsp\lib\drools-lsp-server-jar-with-dependencies.jar

setlocal

set "SCRIPT_DIR=%~dp0"

rem Look for the jar next to the script
for %%f in ("%SCRIPT_DIR%drools-lsp-server-*-jar-with-dependencies.jar") do set "JAR=%%f"

rem If not found, look in lib subdirectory
if not defined JAR (
    for %%f in ("%SCRIPT_DIR%lib\drools-lsp-server-*-jar-with-dependencies.jar") do set "JAR=%%f"
)

if not defined JAR (
    echo drools-lsp: could not find drools-lsp-server-jar-with-dependencies.jar >&2
    echo drools-lsp: expected it next to this script or in a lib\ subdirectory >&2
    exit /b 1
)

java -jar "%JAR%" %*

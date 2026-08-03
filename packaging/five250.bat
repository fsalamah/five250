@echo off
rem Self-contained launcher for the jlink'd distribution (target/dist after "mvn package") -
rem uses the bundled runtime\ next to this script, never whatever Java (if any) happens to be
rem on this machine's PATH. Copy the whole dist folder anywhere and double-click this, or run
rem it from a terminal with the usual five250 CLI args.
setlocal
set DIR=%~dp0
"%DIR%runtime\bin\java.exe" -jar "%DIR%five250.jar" %*

@echo off
rem Self-contained launcher for the jlink'd distribution (target/dist after "mvn package") -
rem uses the bundled runtime\ next to this script, never whatever Java (if any) happens to be
rem on this machine's PATH. Copy the whole dist folder anywhere and double-click this, or run
rem it from a terminal with the usual five250 CLI args.
setlocal
rem Switch the console to the UTF-8 codepage so Arabic/other non-ASCII screen text renders
rem correctly instead of as gibberish (the JVM writes UTF-8 bytes to stdout/stderr - see
rem Cli.main - but the console still needs to be told to interpret them as UTF-8). Quiet the
rem "Active code page: 65001" confirmation line with >nul.
chcp 65001 >nul
set DIR=%~dp0
"%DIR%runtime\bin\java.exe" -jar "%DIR%five250.jar" %*

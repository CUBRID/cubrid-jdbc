@echo off

REM
REM Copyright 2016 CUBRID Corporation
REM

rem build script for MS Windows.
rem
rem Requirements
rem - jdk 1.6 or higher
rem - build tools (ant)

set SHELL_PATH=%~dp0
set GIT_FILE=C:\Program Files\Git\bin\git.exe
set JAVA_FILE=%JAVA_HOME%\bin\java.exe
set ANT_FILE=%ANT_HOME%\bin\ant
set VERSION_FILE=VERSION
set SERIAL_START_DATE=2021-03-30

if "%*" == "clean" GOTO :CHECK_ENV
if "%*" == "" GOTO :CHECK_ENV
if "%*" == "/help" GOTO :SHOW_USAGE
if "%*" == "/h" GOTO :SHOW_USAGE
if "%*" == "/?" GOTO :SHOW_USAGE
echo "[INFO] Unknown Option [%*]"
GOTO :SHOW_USAGE

:CHECK_ENV
echo Checking for requirements...
call :FINDEXEC git.exe GIT_FILE "%GIT_FILE%"

if EXIST "%SHELL_PATH%.git" (
  for /f "delims=" %%i in ('"%GIT_FILE%" -C %SHELL_PATH% rev-list --after %SERIAL_START_DATE% --count HEAD') do set SERIAL_NUMBER=0000%%i
) else (
  set SERIAL_NUMBER=0000
)
set SERIAL_NUMBER=%SERIAL_NUMBER:~-4%

call :FINDEXEC java.exe JAVA_FILE "%JAVA_FILE%"
if "%JAVA_HOME%" == "" (
  echo "[ERROR] set environment variable is required. (JAVA_HOME)"
  GOTO :EOF 
)

call :FINDEXEC ant ANT_PATH "%ANT%"
if "%ANT_PATH%" == "" (
  if "%ANT_HOME%" == "" (
    echo "[ERROR] set environment variable is required. (ANT Or ANT_HOME)"
    GOTO :EOF 
  ) else (
    call :FINDEXEC ant ANT_PATH "%ANT_FILE%"
 )
)

if "%ANT_PATH%" == "" (
  echo "Please check ANT_HOME or ANT(execute file)"
  GOTO :EOF 
) else (
  set ANT=%ANT_PATH%
)
GOTO :BUILD

:BUILD
for /f "delims=" %%i in (%SHELL_PATH%%VERSION_FILE%) do set VERSION=%%i
set VERSION=%VERSION%.%SERIAL_NUMBER%
echo "VERSION = %VERSION%
if "%*" == "clean" (
  "%ANT_PATH%" clean -buildfile %SHELL_PATH%build.xml
) else (
  
  if NOT EXIST "%SHELL_PATH%output" (
    mkdir %SHELL_PATH%output
  )
  echo %VERSION%> %SHELL_PATH%output\VERSION-DIST
  echo.>"%SHELL_PATH%output\CUBRID-JDBC-%VERSION%"
  "%ANT_PATH%" dist-cubrid -buildfile %SHELL_PATH%build.xml -Dbasedir=%SHELL_PATH% -Dversion=%VERSION% -Dsrc=%SHELL_PATH%src
  copy %SHELL_PATH%JDBC-%VERSION%-cubrid.jar %SHELL_PATH%cubrid_jdbc.jar /Y /V
)
GOTO :EOF

:FINDEXEC
if EXIST %3 set %2=%~3
if NOT EXIST %3 for %%X in (%1) do set FOUNDINPATH=%%~$PATH:X
if defined FOUNDINPATH set %2=%FOUNDINPATH:"=%
if NOT defined FOUNDINPATH if NOT EXIST %3 echo Executable [%1] is not found & GOTO :EOF
call echo Executable [%1] is found at [%%%2%%]
set FOUNDINPATH=
GOTO :EOF

:SHOW_USAGE
@echo.Usage: %0 [OPTION]
@echo.Build script for CUBRID JDBC Driver
@echo. OPTIONS
@echo.  clean          Clean (jar and build file)
@echo.  /help /h /?    Display this help message and exit
@echo.
@echo. Examples:
@echo.  %0             # Build JDBC
@echo.  %0 clean       # Clean (jar and build file)
GOTO :EOF

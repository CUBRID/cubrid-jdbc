@echo off

set CUR_PATH=%cd%
set ANT_FILE=%ANT_HOME%\bin\ant
set JAVA_FILE=%JAVA_HOME%\bin\java.exe
set VERSION_FILE=VERSION
echo Checking for requirements...

if "%JAVA_HOME%" == "" (
	echo "JAVA_HOME path is not exist."
	goto :eof 
) else if EXIST %JAVA_FILE% (
	goto :BUILD
) else (
	echo "JAVA file(%JAVA_FILE%) is not exist."
	goto :eof 
)

if "%ANT_HOME%" == "" (
	echo "ANT_HOME is not exist."
) else if exist %ANT_FILE% (
	goto :BUILD
) else (
	echo "ANT excute file is not exist. %ANT_FILE%"
	goto :eof 
)

:BUILD
::echo "ANT excute %ANT_FILE%"
for /f "delims=" %%i in (%CUR_PATH%\%VERSION_FILE%) do set VERSION=%%i

echo "VERSION = %VERSION%
if "%1" == "clean" (
  %ANT_FILE% clean -buildfile ./build.xml
) else (
  if NOT EXIST "%CUR_PATH%\\output" (
    mkdir output
  )
  copy VERSION output\CUBRID-JDBC-%VERSION%
  %ANT_FILE% dist-cubrid -buildfile ./build.xml -Dbasedir=. -Dversion=%VERSION% -Dsrc=./src
)

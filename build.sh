#!/bin/bash
#
#  Copyright 2016 CUBRID Corporation
#

# Build and package script for CUBRID JDBC Driver
# Requirements 
# - Bash shell
# - JDK 1.6 or higher
# - Build tool - ANT

arg=$@
shell_dir="$( cd "$( dirname "$0" )" && pwd -P )"
ant_file=$(which ant)
java_file=$(which java)
serial_start_date=2021-03-30

function show_usage ()
{
  echo "Usage: $0 [OPTIONS]"
  echo "Build script for CUBRID JDBC Driver"
  echo " OPTIONS"
  echo "  clean                Clean (jar and build files)"
  echo "  --help | -h | -?     Display this help message and exit"
  echo ""
  echo " EXAMPLES"
  echo "  $0                   # Build JDBC"
  echo "  $0 clean             # Clean (jar and build files)"
  echo ""
}

function check_env ()
{
  # check Git
  echo "Checking for Git"
  which_git=$(which git)
  [ $? -eq 0 ] && echo "[INFO] Git File [$which_git"] || echo "[ERROR] Git not found"

  echo "Checking Environment Variables(JAVA) and build tools(ANT)"

  if [ "x$JAVA_HOME" != "x" -a "x$JAVA_HOME" != "x" ]; then
    echo "JAVA_HOME [$JAVA_HOME]"
    export PATH="$JAVA_HOME/bin:$PATH"
  elif [ "x$java_file" != "x" ]; then
    echo "[INFO] Used Java File ($java_file)"
  else
    echo "[ERROR] Please check JAVA_HOME Or JAVA PATH" 
    exit 0
  fi

  if [ "x$ant_file" = "x" ]; then
    echo "[ERROR] Pleasse check ANT PATH (Build Tools)"
    exit 0
  fi
}

if [ -z $arg ]; then
  check_env
elif [ $arg = "clean" ]; then
  check_env
  echo "[INFO] Clean Build"
  $ant_file clean -buildfile $shell_dir/build.xml
  rm -fv $shell_dir/cubrid_jdbc.jar
  exit 0
elif [ $arg = "--help" -o  $arg = "-h" -o $arg = "-?" ]; then
  show_usage
  exit 0
else 
  echo "[ERROR] Unknown Option [$arg]"
  show_usage
  exit 0
fi

# check version
echo "[INFO] Checking VERSION"
if [ -f $shell_dir/VERSION ]; then
  version_file=VERSION
  version=$(cat $shell_dir/$version_file)
  serial_number=$(cd $shell_dir && $which_git rev-list --count --after $serial_start_date HEAD | awk '{ printf "%04d", $1 }' 2> /dev/null)
  version=$version.$serial_number
else 
  version="external"
fi

echo "[INFO] VERSION = $version"

if [ ! -d $shell_dir/output ]; then
  mkdir -p $shell_dir/output
fi
echo $version > $shell_dir/output/VERSION-DIST
$ant_file dist-cubrid -buildfile $shell_dir/build.xml -Dbasedir=$shell_dir -Dversion=$version -Dsrc=$shell_dir/src
ln -sf JDBC-$version-cubrid.jar $shell_dir/cubrid_jdbc.jar


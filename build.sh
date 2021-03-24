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
cur_dir=`pwd`
ant_file=$(which ant)
java_file=$(which java)

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
  $ant_file clean -buildfile $cur_dir/build.xml
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
if [ -f $cur_dir/VERSION ]; then
  version_file=VERSION
  version=$(cat $cur_dir/$version_file)
else 
  version="external"
fi

echo "[INFO] VERSION = $version"

if [ ! -d $cur_dir/output ]; then
  mkdir -p $cur_dir/output
fi
cp -rfv $cur_dir/VERSION $cur_dir/output/CUBRID-JDBC-$version
$ant_file dist-cubrid -buildfile $cur_dir/build.xml -Dbasedir=. -Dversion=$version -Dsrc=./src


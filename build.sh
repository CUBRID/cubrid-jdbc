#!/bin/bash
#
#  Copyright 2008 Search Solution Corporation Copyright 2016 CUBRID Corporation
# 
#   Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance 
#   with the License. You may obtain a copy of the License at
# 
#       http://www.apache.org/licenses/LICENSE-2.0
# 
#   Unless required by applicable law or agreed to in writing, software distributed under the License is distributed 
#   on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License 
#   for the specific language governing permissions and limitations under the License.
# 
#

# Build and package script for CUBRID 
# Requirements 
# - Bash shell 
# - Build tool - ant

arg=$@
cur_dir=`pwd`
ant_file=$(which ant)
java_file=$(which java)

function show_usage ()
{
  echo "Usage: $0 [OPTIONS]"
  echo "Build scrtip for CUBRID JDBC Driver"
  echo " OPTIONS"
  echo "  clean		       Clean (jar and build file)"
  echo "  --help | -h | -?     Display this help message and exit"
  echo " EXAMPLES"
  echo "  $0                         # JDBC Build"
  echo "  $0 clean                   # Clean"
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
  echo "[ERROR] Unknown Option ($arg)"
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


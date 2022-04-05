#
#
#  Copyright 2016 CUBRID Corporation
# 
#   Licensed under the Apache License, Version 2.0 (the "License");
#   you may not use this file except in compliance with the License.
#   You may obtain a copy of the License at
# 
#       http://www.apache.org/licenses/LICENSE-2.0
# 
#   Unless required by applicable law or agreed to in writing, software
#   distributed under the License is distributed on an "AS IS" BASIS,
#   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#   See the License for the specific language governing permissions and
#   limitations under the License.
# 
#

cmake_minimum_required(VERSION 2.8)

project(CUBRID-JDBC)

message(STATUS "=========== JDBC ===========")

# check enviroment
include(FindJava)
find_package(Java 1.6 COMPONENTS Development)

find_program(ANT ant PATHS $ENV{ANT_HOME} ENV PATH)
if(NOT ANT)
  message(FATAL_ERROR "The ANT was not found. check PATH or install ant to build JDBC")
endif(NOT ANT)

# check built as sub_directory
if (EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/VERSION)
  set (CUBRID_JDBC_SOURCE_DIR ${CMAKE_CURRENT_SOURCE_DIR})
else (EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/VERSION)
  # version may exist at the parent path
  set (CUBRID_JDBC_SOURCE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../)
endif (EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/VERSION)
set (CUBRID_JDBC_OUTPUT_DIR ${CUBRID_JDBC_SOURCE_DIR}/output)

message(STATUS "[INFO] CUBRID_JDBC_SOURCE_DIR = ${CUBRID_JDBC_SOURCE_DIR}")
message(STATUS "[INFO] CUBRID_JDBC_OUTPUT_DIR = ${CUBRID_JDBC_OUTPUT_DIR}")

add_custom_target(jdbc_build)

add_custom_command(TARGET jdbc_build PRE_BUILD
  COMMAND ${CMAKE_COMMAND} -E make_directory ${CUBRID_JDBC_OUTPUT_DIR}
  COMMAND ${CMAKE_COMMAND} -DCUBRID_JDBC_SOURCE_DIR=${CUBRID_JDBC_SOURCE_DIR} -DCUBRID_JDBC_OUTPUT_DIR=${CUBRID_JDBC_OUTPUT_DIR} -P ${CUBRID_JDBC_SOURCE_DIR}/cmake/gen_version.cmake
  COMMENT "Cleaning CUBRID JDBC project and Generating JDBC VERSION-DIST file ..."
)

# build with ant
add_custom_command(TARGET jdbc_build
  COMMAND ${ANT} dist-cubrid -buildfile ${CMAKE_CURRENT_SOURCE_DIR}/build.xml
  COMMENT "Build JDBC driver with Ant ..."
)

# generate cubrid_jdbc.jar
add_custom_command(TARGET jdbc_build POST_BUILD
  COMMAND ${CMAKE_COMMAND} -DCUBRID_JDBC_SOURCE_DIR=${CUBRID_JDBC_SOURCE_DIR} -DCUBRID_JDBC_OUTPUT_DIR=${CUBRID_JDBC_OUTPUT_DIR} -P ${CUBRID_JDBC_SOURCE_DIR}/cmake/gen_symlink.cmake
)

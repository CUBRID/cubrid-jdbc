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
find_package(Git)

file(STRINGS ${CUBRID_JDBC_SOURCE_DIR}/VERSION CUBRID_JDBC_VERSION)

if(NOT CUBRID_JDBC_VERSION)
    message(FATAL_ERROR "Could not find VERSION file")
endif(NOT CUBRID_JDBC_VERSION)

set(CUBRID_JDBC_START_SERIAL_DATE "2021-03-30")
execute_process(
    COMMAND ${GIT_EXECUTABLE} rev-list --count --after ${CUBRID_JDBC_START_SERIAL_DATE} HEAD
    COMMAND awk "{ printf \"%04d\", $1 }"
    OUTPUT_VARIABLE EXTRA_VERSION RESULT_VARIABLE git_result
    ERROR_QUIET OUTPUT_STRIP_TRAILING_WHITESPACE
    WORKING_DIRECTORY ${CUBRID_JDBC_SOURCE_DIR})

if(git_result)
    message(FATAL_ERROR "Could not get count information from Git")
endif(git_result)

set(CUBRID_JDBC_RELEASE_VERSION ${CUBRID_JDBC_VERSION}.${EXTRA_VERSION})

file(WRITE ${CUBRID_JDBC_OUTPUT_DIR}/VERSION-DIST "${CUBRID_JDBC_RELEASE_VERSION}")
file(TOUCH ${CUBRID_JDBC_OUTPUT_DIR}/CUBRID-JDBC-${CUBRID_JDBC_RELEASE_VERSION})

configure_file (${CUBRID_JDBC_SOURCE_DIR}/cmake/build.properties.in ${CUBRID_JDBC_OUTPUT_DIR}/build.properties)

message ("[INFO] JDBC VERSION = ${CUBRID_JDBC_RELEASE_VERSION}")

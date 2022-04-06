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

file(STRINGS ${CUBRID_JDBC_OUTPUT_DIR}/VERSION-DIST CUBRID_JDBC_VERSION)

if(NOT CUBRID_JDBC_VERSION)
    message(FATAL_ERROR "Could not find VERSION-DIST file")
endif(NOT CUBRID_JDBC_VERSION)

if(UNIX)
  execute_process(
    COMMAND ${CMAKE_COMMAND} -E create_symlink ${CUBRID_JDBC_SOURCE_DIR}/JDBC-${CUBRID_JDBC_VERSION}-cubrid.jar ${CUBRID_JDBC_SOURCE_DIR}/cubrid_jdbc.jar
  )
else(UNIX)
  execute_process(
    COMMAND ${CMAKE_COMMAND} -E copy ${CUBRID_JDBC_SOURCE_DIR}/JDBC-${CUBRID_JDBC_VERSION}-cubrid.jar ${CUBRID_JDBC_SOURCE_DIR}/cubrid_jdbc.jar
  )
endif(UNIX)

message ("[INFO] Generating cubrid_jdbc.jar finished")

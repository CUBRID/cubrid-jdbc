/*
 * Copyright (c) 2016 CUBRID Corporation.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice,
 *   this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 * - Neither the name of the <ORGANIZATION> nor the names of its contributors
 *   may be used to endorse or promote products derived from this software without
 *   specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA,
 * OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY
 * OF SUCH DAMAGE.
 *
 */

package cubrid.jdbc.driver;

import static org.junit.Assert.*;

import java.lang.reflect.Method;
import org.junit.Test;

public class DriverChecklistTest {

    @Test
    public void assertCL05_distributionModeNotUsedInDriver() throws Exception {
        Method[] driverMethods = CUBRIDDriver.class.getDeclaredMethods();
        for (Method m : driverMethods) {
            assertFalse(
                    "CUBRIDDriver should not have a method referencing distribution mode",
                    m.getName().toLowerCase().contains("distributionmode"));
        }
    }

    @Test
    public void assertCL06_driverDoesNotImplementBatchRouting() throws Exception {
        Method[] methods = CUBRIDDriver.class.getDeclaredMethods();
        for (Method m : methods) {
            assertFalse(
                    "CUBRIDDriver should not implement executeBatch routing",
                    m.getName().toLowerCase().contains("executebatch"));
            assertFalse(
                    "CUBRIDDriver should not implement batch routing",
                    m.getName().toLowerCase().contains("batchrout"));
        }
    }

    @Test
    public void assertCL08_driverNotInLbPackage() throws Exception {
        assertFalse(
                "CUBRIDDriver should not be in lb package",
                CUBRIDDriver.class.getPackage().getName().contains("cubrid.jdbc.lb"));
    }
}

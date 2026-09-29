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

package cubrid.jdbc.lb.sql;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for the {@link BuiltinFunctionCatalog} name registry (API + write/session
 * subdivision). Names passed to the API are already upper-cased ASCII (the classifier normalises
 * identifiers before lookup).
 */
public class BuiltinFunctionCatalogTest {

    @Test
    public void readOnlyBuiltinsAreRoSafe() {
        assertTrue(BuiltinFunctionCatalog.isReadOnlyBuiltin("ABS"));
        assertTrue(BuiltinFunctionCatalog.isReadOnlyBuiltin("SUBSTR"));
        assertTrue(BuiltinFunctionCatalog.isReadOnlyBuiltin("COUNT"));
        assertTrue(BuiltinFunctionCatalog.isReadOnlyBuiltin("ROW_NUMBER"));
    }

    @Test
    public void readOnlyBuiltinsExcludeRwAndUserRoutines() {
        assertFalse(BuiltinFunctionCatalog.isReadOnlyBuiltin("SERIAL_NEXT_VALUE"));
        assertFalse(BuiltinFunctionCatalog.isReadOnlyBuiltin("INCR"));
        assertFalse(BuiltinFunctionCatalog.isReadOnlyBuiltin("LAST_INSERT_ID"));
        assertFalse(BuiltinFunctionCatalog.isReadOnlyBuiltin("BLOB_LENGTH"));
        assertFalse(BuiltinFunctionCatalog.isReadOnlyBuiltin("MY_PROC"));
    }

    @Test
    public void writeBuiltinsCoverSerialSessionAndLob() {
        assertTrue(BuiltinFunctionCatalog.isWriteBuiltin("SERIAL_NEXT_VALUE"));
        assertTrue(BuiltinFunctionCatalog.isWriteBuiltin("SERIAL_CURRENT_VALUE"));
        assertTrue(BuiltinFunctionCatalog.isWriteBuiltin("LAST_INSERT_ID"));
        assertTrue(BuiltinFunctionCatalog.isWriteBuiltin("ROW_COUNT"));
        assertTrue(BuiltinFunctionCatalog.isWriteBuiltin("INCR"));
        assertTrue(BuiltinFunctionCatalog.isWriteBuiltin("DECR"));
        assertTrue(BuiltinFunctionCatalog.isWriteBuiltin("BLOB_LENGTH"));
        assertTrue(BuiltinFunctionCatalog.isWriteBuiltin("CLOB_TO_CHAR"));

        assertFalse(BuiltinFunctionCatalog.isWriteBuiltin("ABS"));
        assertFalse(BuiltinFunctionCatalog.isWriteBuiltin("MY_PROC"));
    }

    @Test
    public void writeEffectBuiltinsAreTheMutatingSubset() {
        assertTrue(BuiltinFunctionCatalog.isWriteEffectBuiltin("SERIAL_NEXT_VALUE"));
        assertTrue(BuiltinFunctionCatalog.isWriteEffectBuiltin("INCR"));
        assertTrue(BuiltinFunctionCatalog.isWriteEffectBuiltin("DECR"));

        // Session-dependent / LOB RW built-ins are NOT write-effect (they classify UNKNOWN).
        assertFalse(BuiltinFunctionCatalog.isWriteEffectBuiltin("SERIAL_CURRENT_VALUE"));
        assertFalse(BuiltinFunctionCatalog.isWriteEffectBuiltin("LAST_INSERT_ID"));
        assertFalse(BuiltinFunctionCatalog.isWriteEffectBuiltin("ROW_COUNT"));
        assertFalse(BuiltinFunctionCatalog.isWriteEffectBuiltin("BLOB_LENGTH"));
        assertFalse(BuiltinFunctionCatalog.isWriteEffectBuiltin("ABS"));
    }

    @Test
    public void writeEffectBuiltinsAreSubsetOfWriteBuiltins() {
        final String[] writeEffect = {"SERIAL_NEXT_VALUE", "INCR", "DECR"};
        for (int i = 0; i < writeEffect.length; i++) {
            assertTrue(writeEffect[i], BuiltinFunctionCatalog.isWriteBuiltin(writeEffect[i]));
            assertTrue(writeEffect[i], BuiltinFunctionCatalog.isWriteEffectBuiltin(writeEffect[i]));
        }
    }

    @Test
    public void isBuiltinIsUnionOfRoAndRw() {
        assertTrue(BuiltinFunctionCatalog.isBuiltin("ABS"));
        assertTrue(BuiltinFunctionCatalog.isBuiltin("SERIAL_CURRENT_VALUE"));
        assertTrue(BuiltinFunctionCatalog.isBuiltin("INCR"));
        assertTrue(BuiltinFunctionCatalog.isBuiltin("BLOB_LENGTH"));

        // user routine → not a built-in → RW conservatively at the classifier
        assertFalse(BuiltinFunctionCatalog.isBuiltin("MY_PROC"));
        // SQL syntax keyword and pseudo-columns are not function built-ins
        assertFalse(BuiltinFunctionCatalog.isBuiltin("IN"));
        assertFalse(BuiltinFunctionCatalog.isBuiltin("NEXTVAL"));
    }

    @Test
    public void sqlSyntaxKeywordsAreNotFunctions() {
        assertTrue(BuiltinFunctionCatalog.isSqlSyntax("IN"));
        assertTrue(BuiltinFunctionCatalog.isSqlSyntax("EXISTS"));
        assertTrue(BuiltinFunctionCatalog.isSqlSyntax("ALL"));
        assertTrue(BuiltinFunctionCatalog.isSqlSyntax("OVER"));

        assertFalse(BuiltinFunctionCatalog.isSqlSyntax("ABS"));
        assertFalse(BuiltinFunctionCatalog.isSqlSyntax("MY_PROC"));
    }

    @Test
    public void serialPseudoColumnsAreDetected() {
        assertTrue(BuiltinFunctionCatalog.isSerialPseudoColumn("NEXTVAL"));
        assertTrue(BuiltinFunctionCatalog.isSerialPseudoColumn("NEXT_VALUE"));
        assertTrue(BuiltinFunctionCatalog.isSerialPseudoColumn("CURRVAL"));
        assertTrue(BuiltinFunctionCatalog.isSerialPseudoColumn("CURRENT_VALUE"));

        assertFalse(BuiltinFunctionCatalog.isSerialPseudoColumn("ABS"));
        assertFalse(BuiltinFunctionCatalog.isSerialPseudoColumn("MY_COL"));
    }

    /** NEXTVAL/NEXT_VALUE are write (increment); CURRVAL/CURRENT_VALUE are session-dependent. */
    @Test
    public void serialIncrementPseudoColumnsAreTheWriteSubset() {
        assertTrue(BuiltinFunctionCatalog.isSerialIncrementPseudoColumn("NEXTVAL"));
        assertTrue(BuiltinFunctionCatalog.isSerialIncrementPseudoColumn("NEXT_VALUE"));

        assertFalse(BuiltinFunctionCatalog.isSerialIncrementPseudoColumn("CURRVAL"));
        assertFalse(BuiltinFunctionCatalog.isSerialIncrementPseudoColumn("CURRENT_VALUE"));
        assertFalse(BuiltinFunctionCatalog.isSerialIncrementPseudoColumn("ABS"));
    }

    @Test
    public void nullAndEmptyNamesNeverMatch() {
        assertFalse(BuiltinFunctionCatalog.isReadOnlyBuiltin(null));
        assertFalse(BuiltinFunctionCatalog.isReadOnlyBuiltin(""));
        assertFalse(BuiltinFunctionCatalog.isWriteBuiltin(null));
        assertFalse(BuiltinFunctionCatalog.isWriteEffectBuiltin(null));
        assertFalse(BuiltinFunctionCatalog.isBuiltin(null));
        assertFalse(BuiltinFunctionCatalog.isSqlSyntax(null));
        assertFalse(BuiltinFunctionCatalog.isSerialPseudoColumn(""));
        assertFalse(BuiltinFunctionCatalog.isSerialIncrementPseudoColumn(""));
    }
}

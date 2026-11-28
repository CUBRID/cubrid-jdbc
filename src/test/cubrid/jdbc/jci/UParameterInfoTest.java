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

package cubrid.jdbc.jci;

import static org.junit.Assert.assertArrayEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class UParameterInfoTest {

    @Test
    public void readsTheNameAfterPrecision() throws Exception {
        UInputBuffer in = reply("Num", "Total");

        String[] names = UParameterInfo.readNames(in, in.getResCode(), "UTF-8");

        assertArrayEquals(new String[] {"Num", "Total"}, names);
    }

    @Test
    public void anUnnamedMarkerHasAnEmptyName() throws Exception {
        UInputBuffer in = reply("", "a");

        String[] names = UParameterInfo.readNames(in, in.getResCode(), "UTF-8");

        assertArrayEquals(new String[] {"", "a"}, names);
    }

    @Test
    public void readsAMultibyteNameInTheConnectionCharset() throws Exception {
        UInputBuffer in = reply("가격");

        String[] names = UParameterInfo.readNames(in, in.getResCode(), "UTF-8");

        assertArrayEquals(new String[] {"가격"}, names);
    }

    /* PARAMETER_INFO reply: count, then mode, 2-byte type, scale, precision and name per marker */
    private static UInputBuffer reply(String... names) throws IOException, UJciException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(body);
        out.writeInt(names.length);
        for (String name : names) {
            out.writeByte(2);
            out.writeByte(0x80);
            out.writeByte(7);
            out.writeShort(2);
            out.writeInt(15);
            byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
            out.writeInt(bytes.length + 1);
            out.write(bytes);
            out.writeByte(0);
        }

        ByteArrayOutputStream message = new ByteArrayOutputStream();
        DataOutputStream header = new DataOutputStream(message);
        header.writeInt(body.size());
        header.write(new byte[4]);
        header.write(body.toByteArray());

        UTimedDataInputStream input =
                new UTimedDataInputStream(
                        new ByteArrayInputStream(message.toByteArray()), "localhost", 0);
        return new UInputBuffer(input, new NoSocket());
    }

    /** A socket with nothing behind it: the reply is read from memory. */
    private static final class NoSocket extends UConnection {

        private NoSocket() {
            errorHandler = new UError(this);
        }

        @Override
        public int getPrepStmtCacheSize() {
            return 0;
        }

        public void endTransaction(final boolean type) {}

        protected void closeInternal() {}

        public void setAutoCommit(final boolean autoCommit) {}

        public boolean getAutoCommit() {
            return true;
        }
    }
}

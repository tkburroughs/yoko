/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
*  contributor license agreements.  See the NOTICE file distributed with
*  this work for additional information regarding copyright ownership.
*  The ASF licenses this file to You under the Apache License, Version 2.0
*  (the "License"); you may not use this file except in compliance with
*  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.yoko.orb.OB;

import org.apache.yoko.orb.OCI.ReadBuffer;

final class UTF16Reader extends CodeSetReader {
    private int Flags_ = 0;

    public char read_char(ReadBuffer readBuffer) {
        return (char) (readBuffer.readByte() & 0xff);
    }

    public char read_wchar(ReadBuffer readBuffer, int len) {
        // read the first wchar assuming big endian
        char v = (char) ((readBuffer.readByte() & 0xff) << 8);
        v |= (char) ((readBuffer.readByte() & 0xff));

        //
        // check if this was a BOM
        //
        if (((Flags_ & CodeSetReader.FIRST_CHAR) != 0)
                && (v == (char) 0xFEFF)) {
            //
            // it was a big endian BOM
            //
            v = (char) ((readBuffer.readByte() & 0xff) << 8);
            v |= (char) ((readBuffer.readByte() & 0xff));
        } else if (((Flags_ & CodeSetReader.FIRST_CHAR) != 0)
                && (v == (char) 0xFFFE)) {
            //
            // it was a little endian BOM
            //
            v = (char) ((readBuffer.readByte() & 0xff));
            v |= (char) ((readBuffer.readByte() & 0xff) << 8);

            //
            // enable the little endian reader flag
            //
            Flags_ |= CodeSetReader.L_ENDIAN;
        } else if ((Flags_ & CodeSetReader.L_ENDIAN) != 0) {
            //
            // swap the character input
            //
            v = (char) (((v >>> 8) & 0xff) | ((v << 8) & 0xff));
        }

        //
        // check for the surrogate paired character
        //
        if ((v >= (char) 0xD800) && (v <= (char) 0xDFFF)) {
            //
            // it was a surrogate paired character that we don't support
            //
            Assert._OB_assert(false);
        }

        //
        // turn off the first character reading
        //
        Flags_ &= ~CodeSetReader.FIRST_CHAR;

        return v;
    }

    public int count_wchar(char first) {
        // if we're the first character and this is a BOM, then we need to return 4
        if (((Flags_ & CodeSetReader.FIRST_CHAR) != 0)
                && ((first == (char) 0xFEFF) || first == (char) 0xFFFE))
            return 4;
        return 2;
    }

    public void set_flags(int flags) {
        Flags_ = flags;
    }
}

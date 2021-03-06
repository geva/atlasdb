/**
 * Copyright 2017 Palantir Technologies
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.palantir.atlasdb.keyvalue.api;

import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.palantir.atlasdb.encoding.PtBytes;

public class CheckAndSetException extends RuntimeException {
    private static final Logger log = LoggerFactory.getLogger(CheckAndSetException.class);

    private static final long serialVersionUID = 1L;

    private final Cell key;
    private final byte[] expectedValue;
    private final List<byte[]> actualValues;

    public CheckAndSetException(String msg, Throwable ex) {
        this(msg, ex, null, null, null);
    }

    public CheckAndSetException(String msg) {
        this(msg, null, null, null);
    }

    public CheckAndSetException(String msg, Throwable ex, Cell key, byte[] expectedValue, List<byte[]> actualValues) {
        super(msg, ex);
        this.key = key;
        this.expectedValue = expectedValue;
        this.actualValues = actualValues;
    }

    public CheckAndSetException(String msg, Cell key, byte[] expectedValue, List<byte[]> actualValues) {
        super(msg);
        this.key = key;
        this.expectedValue = expectedValue;
        this.actualValues = actualValues;
    }

    public CheckAndSetException(Cell key, TableReference table, byte[] expected, List<byte[]> actual) {
        super(createAndLogExceptionMessage(key, table, expected, actual));
        this.key = key;
        this.expectedValue = expected;
        this.actualValues = actual;
    }

    private static String createAndLogExceptionMessage(
            Cell key,
            TableReference table,
            byte[] expected,
            List<byte[]> actual) {
        String repeatWarning = "If this is happening repeatedly, your program may be out of sync with the database.";
        String formatStr = "The cell {} in table {} has an unexpected value. Expected '{}' but got '{}'. "
                + repeatWarning;
        log.error(formatStr, key, table, PtBytes.encodeHexString(expected), encodeHexStrings(actual));
        return String.format("Unexpected value observed in table %s. %s", table, repeatWarning);
    }

    private static List<String> encodeHexStrings(List<byte[]> values) {
        return values.stream().map(PtBytes::encodeHexString).collect(Collectors.toList());
    }

    public Cell getKey() {
        return key;
    }

    public byte[] getExpectedValue() {
        return expectedValue;
    }

    public List<byte[]> getActualValues() {
        return actualValues;
    }
}

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 *   SPDX-License-Identifier: Apache-2.0
 */

package org.apache.jena.fuseki.servlets;

import static java.lang.String.format;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.apache.jena.web.HttpSC;

/** Size limits for SHACL validation request shapes. */
public final class SHACLRequestSize {
    public static final String SYSTEM_PROPERTY_MAX_REQUEST_SIZE = "jena.fuseki.shacl.maxRequestSize";
    public static final long DEFAULT_MAX_REQUEST_SIZE = 64L * 1024L * 1024L;

    private SHACLRequestSize() {}

    static void rejectIfContentLengthExceeds(HttpAction action) {
        long limit = maxRequestSize();
        long len = action.getRequestContentLengthLong();
        if ( isEnabled(limit) && len > limit )
            tooLarge(format("SHACL request Content-Length %d exceeds configured limit %d", len, limit));
    }

    static InputStream limitInputStream(InputStream input, String description) {
        long limit = maxRequestSize();
        if ( ! isEnabled(limit) )
            return input;
        return new LimitedInputStream(input, limit, description);
    }

    static void tooLarge(SizeLimitExceededException ex) {
        tooLarge(ex.getMessage());
    }

    private static void tooLarge(String message) {
        ServletOps.error(HttpSC.PAYLOAD_TOO_LARGE_413, message);
    }

    private static long maxRequestSize() {
        Long value = Long.getLong(SYSTEM_PROPERTY_MAX_REQUEST_SIZE);
        return value == null ? DEFAULT_MAX_REQUEST_SIZE : value;
    }

    private static boolean isEnabled(long limit) {
        return limit >= 0;
    }

    static class SizeLimitExceededException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        SizeLimitExceededException(String message) {
            super(message);
        }
    }

    private static class LimitedInputStream extends FilterInputStream {
        private final long limit;
        private final String description;
        private long count = 0;

        LimitedInputStream(InputStream input, long limit, String description) {
            super(input);
            this.limit = limit;
            this.description = description;
        }

        @Override
        public int read() throws IOException {
            int x = super.read();
            if ( x != -1 )
                countBytes(1);
            return x;
        }

        @Override
        public int read(byte[] bytes, int off, int len) throws IOException {
            int x = super.read(bytes, off, len);
            if ( x > 0 )
                countBytes(x);
            return x;
        }

        @Override
        public long skip(long n) throws IOException {
            long x = super.skip(n);
            if ( x > 0 )
                countBytes(x);
            return x;
        }

        private void countBytes(long bytes) {
            if ( bytes > limit - count )
                throw new SizeLimitExceededException(format("%s exceeds configured limit %d", description, limit));
            count += bytes;
        }
    }
}

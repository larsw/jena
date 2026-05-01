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

package org.apache.jena.fuseki.validation;

import static java.lang.String.format;

import java.io.IOException;
import java.io.Reader;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.jena.fuseki.servlets.ServletOps;
import org.apache.jena.web.HttpSC;

/** Size limits for Fuseki validator request text. */
public final class ValidationRequestSize {
    public static final String SYSTEM_PROPERTY_MAX_REQUEST_SIZE = "jena.fuseki.validation.maxRequestSize";
    public static final long DEFAULT_MAX_REQUEST_SIZE = 4L * 1024L * 1024L;

    private ValidationRequestSize() {}

    public static boolean rejectIfContentLengthExceeds(HttpServletRequest request, HttpServletResponse response) {
        long limit = maxRequestSize();
        long len = request.getContentLengthLong();
        if ( isEnabled(limit) && len > limit ) {
            ServletOps.responseSendError(response, HttpSC.PAYLOAD_TOO_LARGE_413,
                                         format("Validation request Content-Length %d exceeds configured limit %d",
                                                len, limit));
            return true;
        }
        return false;
    }

    public static boolean rejectIfStringExceeds(String string, String description, HttpServletResponse response) {
        String message = messageIfStringExceeds(string, description);
        if ( message == null )
            return false;
        ServletOps.responseSendError(response, HttpSC.PAYLOAD_TOO_LARGE_413, message);
        return true;
    }

    public static boolean rejectIfStringsExceed(String[] strings, String description, HttpServletResponse response) {
        String message = messageIfStringsExceed(strings, description);
        if ( message == null )
            return false;
        ServletOps.responseSendError(response, HttpSC.PAYLOAD_TOO_LARGE_413, message);
        return true;
    }

    public static String readString(Reader reader, String description,
                                    HttpServletResponse response) throws IOException {
        long limit = maxRequestSize();
        StringBuilder builder = new StringBuilder();
        char[] chars = new char[8192];
        long len = 0;
        for (;;) {
            int x = reader.read(chars);
            if ( x == -1 )
                return builder.toString();
            String string = new String(chars, 0, x);
            if ( isEnabled(limit) ) {
                len = utf8Length(string, limit, len);
                if ( len > limit ) {
                    ServletOps.responseSendError(response, HttpSC.PAYLOAD_TOO_LARGE_413,
                                                 format("%s size exceeds configured limit %d", description, limit));
                    return null;
                }
            }
            builder.append(chars, 0, x);
        }
    }

    public static void rejectIfStringExceeds(String string, String description) {
        String message = messageIfStringExceeds(string, description);
        if ( message != null )
            ServletOps.error(HttpSC.PAYLOAD_TOO_LARGE_413, message);
    }

    public static void rejectIfStringsExceed(String[] strings, String description) {
        String message = messageIfStringsExceed(strings, description);
        if ( message != null )
            ServletOps.error(HttpSC.PAYLOAD_TOO_LARGE_413, message);
    }

    private static String messageIfStringExceeds(String string, String description) {
        if ( string == null )
            return null;
        return messageIfStringsExceed(new String[] { string }, description);
    }

    private static String messageIfStringsExceed(String[] strings, String description) {
        long limit = maxRequestSize();
        if ( ! isEnabled(limit) || strings == null )
            return null;
        long len = 0;
        for ( String string : strings ) {
            if ( string == null )
                continue;
            len = utf8Length(string, limit, len);
            if ( len > limit )
                break;
        }
        if ( len > limit )
            return format("%s size exceeds configured limit %d", description, limit);
        return null;
    }

    private static long maxRequestSize() {
        Long value = Long.getLong(SYSTEM_PROPERTY_MAX_REQUEST_SIZE);
        return value == null ? DEFAULT_MAX_REQUEST_SIZE : value;
    }

    private static boolean isEnabled(long limit) {
        return limit >= 0;
    }

    private static long utf8Length(String string, long limit, long initialLength) {
        long len = initialLength;
        for ( int i = 0; i < string.length(); i++ ) {
            char ch = string.charAt(i);
            if ( ch <= 0x7F )
                len++;
            else if ( ch <= 0x7FF )
                len += 2;
            else if ( Character.isHighSurrogate(ch) && i+1 < string.length()
                    && Character.isLowSurrogate(string.charAt(i+1)) ) {
                len += 4;
                i++;
            } else
                len += 3;
            if ( len > limit )
                return len;
        }
        return len;
    }
}

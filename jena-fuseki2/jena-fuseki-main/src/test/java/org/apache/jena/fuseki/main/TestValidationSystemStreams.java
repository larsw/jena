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

package org.apache.jena.fuseki.main;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.InputStream;
import java.io.PrintStream;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import org.apache.jena.fuseki.validation.DataValidator;
import org.apache.jena.fuseki.validation.IRIValidator;
import org.apache.jena.fuseki.validation.LangTagValidator;
import org.apache.jena.http.HttpLib;
import org.apache.jena.web.HttpSC;

public class TestValidationSystemStreams {
    @Test
    public void iriValidatorDoesNotChangeSystemStreams() {
        withValidationServer(server ->
            assertSystemStreamsUnchanged(() ->
                assertEquals(HttpSC.OK_200, get(server.serverURL()+"$/validate/iri?iri="+urlEncode("http://example/")))
            )
        );
    }

    @Test
    public void langTagValidatorDoesNotChangeSystemStreams() {
        withValidationServer(server ->
            assertSystemStreamsUnchanged(() ->
                assertEquals(HttpSC.OK_200, get(server.serverURL()+"$/validate/langtag?lang=en-GB"))
            )
        );
    }

    @Test
    public void dataValidatorDoesNotChangeSystemStreams() {
        withValidationServer(server ->
            assertSystemStreamsUnchanged(() ->
                assertEquals(HttpSC.OK_200, get(server.serverURL()+"$/validate/data?data="+urlEncode("<s> <p> <o> .")))
            )
        );
    }

    private static void withValidationServer(ServerAction action) {
        FusekiServer server = FusekiServer.create()
                .port(0)
                .addServlet("/$/validate/iri", new IRIValidator())
                .addServlet("/$/validate/langtag", new LangTagValidator())
                .addServlet("/$/validate/data", new DataValidator())
                .build()
                .start();
        try {
            action.run(server);
        } finally {
            server.stop();
        }
    }

    private static void assertSystemStreamsUnchanged(Runnable action) {
        PrintStream stdout = System.out;
        PrintStream stderr = System.err;
        try {
            action.run();
        } finally {
            assertSame(stdout, System.out);
            assertSame(stderr, System.err);
        }
    }

    private static int get(String url) {
        HttpRequest request = HttpRequest.newBuilder(HttpLib.toRequestURI(url)).GET().build();
        HttpResponse<InputStream> response = HttpLib.executeJDK(HttpClient.newHttpClient(), request,
                                                                HttpResponse.BodyHandlers.ofInputStream());
        HttpLib.finishResponse(response);
        return response.statusCode();
    }

    private static String urlEncode(String string) {
        return URLEncoder.encode(string, UTF_8);
    }

    private interface ServerAction {
        void run(FusekiServer server);
    }
}

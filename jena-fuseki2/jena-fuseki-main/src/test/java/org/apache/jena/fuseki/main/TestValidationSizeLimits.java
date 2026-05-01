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

import java.io.InputStream;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import org.apache.jena.fuseki.validation.DataValidator;
import org.apache.jena.fuseki.validation.IRIValidator;
import org.apache.jena.fuseki.validation.LangTagValidator;
import org.apache.jena.fuseki.validation.QueryValidator;
import org.apache.jena.fuseki.validation.UpdateValidator;
import org.apache.jena.fuseki.validation.ValidationRequestSize;
import org.apache.jena.http.HttpLib;
import org.apache.jena.web.HttpSC;

public class TestValidationSizeLimits {
    @Test
    public void queryValidatorSizeLimit() {
        withValidationServer(server -> {
            withSystemProperty(ValidationRequestSize.SYSTEM_PROPERTY_MAX_REQUEST_SIZE, "4", () -> {
                int sc = get(server.serverURL()+"$/validate/query?query="+urlEncode("ASK { }"));
                assertEquals(HttpSC.PAYLOAD_TOO_LARGE_413, sc);
            });
        });
    }

    @Test
    public void updateValidatorSizeLimit() {
        withValidationServer(server -> {
            withSystemProperty(ValidationRequestSize.SYSTEM_PROPERTY_MAX_REQUEST_SIZE, "8", () -> {
                int sc = get(server.serverURL()+"$/validate/update?update="+urlEncode("INSERT DATA { <s> <p> <o> }"));
                assertEquals(HttpSC.PAYLOAD_TOO_LARGE_413, sc);
            });
        });
    }

    @Test
    public void dataValidatorSizeLimit() {
        withValidationServer(server -> {
            withSystemProperty(ValidationRequestSize.SYSTEM_PROPERTY_MAX_REQUEST_SIZE, "4", () -> {
                int sc = get(server.serverURL()+"$/validate/data?data="+urlEncode("<s> <p> <o> ."));
                assertEquals(HttpSC.PAYLOAD_TOO_LARGE_413, sc);
            });
        });
    }

    @Test
    public void iriValidatorSizeLimit() {
        withValidationServer(server -> {
            withSystemProperty(ValidationRequestSize.SYSTEM_PROPERTY_MAX_REQUEST_SIZE, "4", () -> {
                int sc = get(server.serverURL()+"$/validate/iri?iri="+urlEncode("http://example/"));
                assertEquals(HttpSC.PAYLOAD_TOO_LARGE_413, sc);
            });
        });
    }

    @Test
    public void langTagValidatorSizeLimit() {
        withValidationServer(server -> {
            withSystemProperty(ValidationRequestSize.SYSTEM_PROPERTY_MAX_REQUEST_SIZE, "4", () -> {
                int sc = get(server.serverURL()+"$/validate/langtag?lang=en-GB");
                assertEquals(HttpSC.PAYLOAD_TOO_LARGE_413, sc);
            });
        });
    }

    private static void withValidationServer(ServerAction action) {
        FusekiServer server = FusekiServer.create()
                .port(0)
                .addServlet("/$/validate/query", new QueryValidator())
                .addServlet("/$/validate/update", new UpdateValidator())
                .addServlet("/$/validate/data", new DataValidator())
                .addServlet("/$/validate/iri", new IRIValidator())
                .addServlet("/$/validate/langtag", new LangTagValidator())
                .build()
                .start();
        try {
            action.run(server);
        } finally {
            server.stop();
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

    private static void withSystemProperty(String property, String value, Runnable action) {
        String oldValue = System.getProperty(property);
        System.setProperty(property, value);
        try {
            action.run();
        } finally {
            if ( oldValue == null )
                System.clearProperty(property);
            else
                System.setProperty(property, oldValue);
        }
    }

    private interface ServerAction {
        void run(FusekiServer server);
    }
}

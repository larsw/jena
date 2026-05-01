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

package org.apache.jena.fuseki.validation.html;

import static org.apache.jena.fuseki.validation.html.ValidatorHtmlLib.*;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.jena.iri3986.provider.IRIProvider3986;
import org.apache.jena.irix.IRIException;
import org.apache.jena.irix.IRIProvider;
import org.apache.jena.irix.IRIx;

public class IRIValidatorHTML
{
    private IRIValidatorHTML()
    { }

    static final String paramIRI      = "iri";

    public static void executeHTML(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        try {
            String[] args = httpRequest.getParameterValues(paramIRI);

            if ( args == null || args.length == 0 )
                httpResponse.sendError(HttpServletResponse.SC_BAD_REQUEST, "No ?iri= parameter");

            ServletOutputStream outStream = httpResponse.getOutputStream();
            PrintStream out = new PrintStream(outStream, false, StandardCharsets.UTF_8);

            setHeaders(httpResponse);

            outStream.println("<html>");
            printHead(outStream, "Jena IRI Validator Report");
            outStream.println("<body>");

            outStream.println("<h1>IRI Report</h1>");
            startFixed(outStream);

            try {
                boolean first = true;
                IRIProvider provider = new IRIProvider3986();

                for ( String iriStr : args ) {
                    if ( iriStr.startsWith("<") ) {
                        iriStr = iriStr.substring(1);
                        if ( iriStr.endsWith(">") )
                            iriStr = iriStr.substring(0,iriStr.length()-1);
                    }
                    if ( !first )
                        out.println();
                    first = false;
                    try {
                        IRIx iri = provider.create(iriStr);
                        out.println(iriStr + " ==> " + iri);
                        if ( iri.isRelative() )
                            out.println("Relative IRI: " + iriStr);

                        if ( iri.hasViolations() ) {
                            out.println();
                            iri.handleViolations((error,msg)->{
                                String str = htmlQuote(msg);
                                String category = (error)?"Error":"Warning";
                                out.printf("  %-7s : %s\n", category, str);
                            });
                        }
                    } catch (IRIException ex) {
                        out.println(iriStr);
                        out.println("Bad IRI: "+ex.getMessage());
                    }
                }
            } finally {
                finishFixed(outStream);
            }

            outStream.println("</body>");
            outStream.println("</html>");
        } catch (IOException ex) {}
    }
}

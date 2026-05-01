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
import static org.apache.jena.fuseki.servlets.GraphTarget.determineTarget;

import java.io.IOException;
import java.io.InputStream;

import org.apache.jena.atlas.io.IO;
import org.apache.jena.atlas.web.ContentType;
import org.apache.jena.atlas.web.MediaType;
import org.apache.jena.fuseki.DEF;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFLanguages;
import org.apache.jena.riot.RiotParseException;
import org.apache.jena.riot.WebContent;
import org.apache.jena.riot.system.StreamRDF;
import org.apache.jena.riot.system.StreamRDFLib;
import org.apache.jena.riot.web.HttpNames;
import org.apache.jena.shacl.ShaclValidator;
import org.apache.jena.shacl.Shapes;
import org.apache.jena.shacl.ValidationReport;
import org.apache.jena.sparql.graph.GraphFactory;
import org.apache.jena.web.HttpSC;

/**
 * SHACL validation service. Receives a shapes file and validates a graph named in the
 * {@code ?graph=} parameter.
 * <p>
 * {@code ?graph=} can be any graph name, or one of the words "default" or "union" (without quotes)
 * to indicate the default graph, which is also the default and the dataset union graph.
 * <p>
 * Optional parameter {@code ?target=} specifies the target node for the validation report.
 */
public class SHACL_Validation extends BaseActionREST { //ActionREST {

    public SHACL_Validation() {}

    @Override
    protected void doPost(HttpAction action) {
        // Response syntax
        MediaType mediaType = ActionLib.contentNegotation(action, DEF.rdfOffer, DEF.acceptTurtle);
        Lang lang = RDFLanguages.contentTypeToLang(mediaType.getContentTypeStr());
        if ( lang == null )
            lang = RDFLanguages.TTL;

        String targetNodeStr = action.getRequestParameter(HttpNames.paramTarget);

        action.beginRead();
        try {
            GraphTarget graphTarget = determineTarget(action.getActiveDSG(), action);
            if ( ! graphTarget.exists() )
                ServletOps.errorNotFound("No data graph: "+graphTarget.label());
            Graph data = graphTarget.graph();
            Graph shapesGraph = readShapesGraph(action, Lang.TTL);

            Node targetNode = null;
            if ( targetNodeStr != null ) {
                String x = data.getPrefixMapping().expandPrefix(targetNodeStr);
                targetNode = NodeFactory.createURI(x);
            }

            Shapes shapes = Shapes.parse(shapesGraph);
            ValidationReport report = ( targetNode == null )
                ? ShaclValidator.get().validate(shapesGraph, data)
                : ShaclValidator.get().validate(shapesGraph, data, targetNode);

            if ( report.conforms() )
                action.log.info(format("[%d] shacl: conforms", action.id));
            else
                action.log.info(format("[%d] shacl: %d validation errors", action.id, report.getEntries().size()));
            report.getEntries().size();
            action.setResponseStatus(HttpSC.OK_200);
            ActionLib.graphResponse(action, report.getGraph(), lang);
        } finally {
            action.endRead();
        }
    }

    private static Graph readShapesGraph(HttpAction action, Lang defaultLang) {
        ContentType ct = ActionLib.getContentType(action);
        Lang lang;

        if ( ct == null || ct.getContentTypeStr().isEmpty() ) {
            lang = defaultLang;
        } else if ( ct.equals(WebContent.ctHTMLForm)) {
            ServletOps.errorBadRequest("HTML Form data sent to SHACL validation server");
            return null;
        } else {
            lang = RDFLanguages.contentTypeToLang(ct.getContentTypeStr());
            if ( lang == null )
                lang = defaultLang;
        }

        SHACLRequestSize.rejectIfContentLengthExceeds(action);

        Graph graph = GraphFactory.createDefaultGraph();
        StreamRDF dest = StreamRDFLib.graph(graph);
        try {
            InputStream input = SHACLRequestSize.limitInputStream(action.getRequestInputStream(),
                                                                  "SHACL shapes request body");
            ActionLib.parse(action, dest, input, lang, null);
            return graph;
        } catch (SHACLRequestSize.SizeLimitExceededException ex) {
            ActionLib.consumeBody(action);
            SHACLRequestSize.tooLarge(ex);
            return null;
        } catch (RiotParseException ex) {
            ActionLib.consumeBody(action);
            ServletOps.errorParseError(ex);
            return null;
        } catch (IOException ex) {
            IO.exception(ex);
            return null;
        }
    }
}

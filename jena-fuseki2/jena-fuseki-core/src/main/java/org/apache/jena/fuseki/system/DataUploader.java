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

package org.apache.jena.fuseki.system;

import static java.lang.String.format;
import static org.apache.jena.riot.WebContent.ctMultipartFormData;
import static org.apache.jena.riot.WebContent.ctMultipartMixed;
import static org.apache.jena.riot.WebContent.ctTextPlain;
import static org.apache.jena.riot.WebContent.matchContentType;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.fileupload2.core.FileItemInput;
import org.apache.commons.fileupload2.core.FileItemInputIterator;
import org.apache.commons.fileupload2.core.FileUploadSizeException;
import org.apache.commons.fileupload2.jakarta.servlet6.JakartaServletFileUpload;
import org.apache.jena.atlas.io.IO;
import org.apache.jena.atlas.web.ContentType;
import org.apache.jena.fuseki.servlets.*;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFLanguages;
import org.apache.jena.riot.RiotParseException;
import org.apache.jena.riot.lang.StreamRDFCounting;
import org.apache.jena.riot.system.StreamRDF;
import org.apache.jena.riot.system.StreamRDFLib;
import org.apache.jena.web.HttpSC;

/**
 * Functions to load data to a StreamRDF.
 * <p>
 * This is used for {@link GSP_RW Graph Store Protocol PUT/POST},
 * including the extension to dataset (quads) loads
 * and also {@link UploadRDF} the direct upload service.
 * @see UploadRDF
 */
public class DataUploader {
    public static final String SYSTEM_PROPERTY_MAX_REQUEST_SIZE = "jena.fuseki.upload.maxRequestSize";
    public static final String SYSTEM_PROPERTY_MAX_FILE_SIZE = "jena.fuseki.upload.maxFileSize";
    public static final String SYSTEM_PROPERTY_MAX_FILES = "jena.fuseki.upload.maxFiles";
    public static final String SYSTEM_PROPERTY_MAX_EXPANDED_SIZE = "jena.fuseki.upload.maxExpandedSize";

    public static final long DEFAULT_MAX_REQUEST_SIZE = 1024L * 1024L * 1024L;
    public static final long DEFAULT_MAX_FILE_SIZE = DEFAULT_MAX_REQUEST_SIZE;
    public static final long DEFAULT_MAX_EXPANDED_SIZE = DEFAULT_MAX_REQUEST_SIZE;
    public static final long DEFAULT_MAX_FILES = 1000;

    /**
     * Parse the body contents to the {@link StreamRDF}.
     * <p>
     * This function is used by {@link GSP_RW} and {@link UploadRDF}.
     * <p>
     * This function assumes it is inside a transaction.
     * <p>
     * Supports Content-Type, with data in the body, as well as
     * {@code multipart/mixed} (<a href="https://tools.ietf.org/html/rfc204">RFC 2046</a>) and
     * {@code multipart/form-data} (<a href="https://tools.ietf.org/html/rfc1867">RFC 1867</a>)
     * with a file part.
     * @throws RiotParseException RiotParseException
     */
    public static UploadDetails incomingData(HttpAction action, StreamRDF dest) {
        ContentType ct = ActionLib.getContentType(action);
        UploadLimits limits = uploadLimits();

        if ( ct == null ) {
            ServletOps.errorBadRequest("No content type");
            return null;
        }

        if ( matchContentType(ctMultipartFormData, ct) || matchContentType(ctMultipartMixed, ct) ) {
            // Multipart
            return multipartCommonsFileUpload(action, dest);
        }

        rejectIfContentLengthExceeds(action, limits.maxRequestSize);

        // Single graph or quads in body.
        String base = ActionLib.wholeRequestURL(action.getRequest());
        Lang lang = RDFLanguages.contentTypeToLang(ct.getContentTypeStr());
        if ( lang == null ) {
            ServletOps.errorBadRequest("Unknown content type for triples: " + ct);
            return null;
        }
        long len = action.getRequestContentLengthLong();

        StreamRDFCounting countingDest = StreamRDFLib.count(dest);
        try {
            InputStream input = limitInputStream(action.getRequestInputStream(), limits.maxExpandedSize, "Uploaded RDF data");
            ActionLib.parse(action, countingDest, input, lang, base);
            UploadDetails details = new UploadDetails(countingDest.count(), countingDest.countTriples(),countingDest.countQuads());
            action.log.info(format("[%d] Body: Content-Length=%d, Content-Type=%s, Charset=%s => %s : %s",
                                   action.id, len, ct.getContentTypeStr(), ct.getCharset(), lang.getName(),
                                   details.detailsStr()));
            return details;
        } catch (UploadLimitExceededException ex) {
            uploadTooLarge(ex.getMessage());
            return null;
        } catch (IOException ex) {
            IO.exception(ex);
            return null;
        } catch (RiotParseException ex) {
            action.log.info(format("[%d] Attempt to load: Content-Length=%d, Content-Type=%s, Charset=%s => %s",
                                   action.id, len, ct.getContentTypeStr(), ct.getCharset(), ex.getMessage()));
            // Exhaust input.
            ActionLib.consumeBody(action);
            throw ex;
        }
    }

    // Previously, Jena has used HttpServletRequests.getParts.
    // Each application server (Tomcat and Jetty) has special configuration.
    // Use Apache Commons FileUpload as the multipart parser as it is portable.

    /**
     * Process an HTTP upload of RDF files (triples or quads) with content type
     * "multipart/form-data" or "multipart/mixed".
     * <p>
     * Form data (content-disposition: form-data; name="...") is rejected.
     * <p>
     * Data is streamed straight into the destination graph or dataset.
     * <p>
     * This function assumes it is inside a transaction.
     */
    private static UploadDetails multipartCommonsFileUpload(HttpAction action, StreamRDF dest) {
        String base = ActionLib.wholeRequestURL(action.getRequest());
        StreamRDFCounting countingDest =  StreamRDFLib.count(dest);
        UploadLimits limits = uploadLimits();
        rejectIfContentLengthExceeds(action, limits.maxRequestSize);

        // We only use the request body parsing capability of a ServletFileUpload.
        JakartaServletFileUpload<?,?> upload = new JakartaServletFileUpload<>();
        configureUploadLimits(upload, limits);
        try {
            FileItemInputIterator iter = upload.getItemIterator(action.getRequest());
            while (iter.hasNext()) {
                FileItemInput part = iter.next();

                if (part.isFormField()) {
                    // Form field - this code only supports multipart file upload.
                    String fieldName = part.getFieldName();
                    InputStream stream = part.getInputStream();
                    String value = IO.readWholeFileAsUTF8(stream);
                    // This code is currently used to put multiple files into a single destination.
                    // Additional field/values do not make sense.
                    ServletOps.errorBadRequest(format("Only files accepted in multipart file upload (got %s=%s)", fieldName, value));
                    // errorBadRequest does not return.
                    return null;
                }

                InputStream input = part.getInputStream();
                String contentTypeHeader = part.getContentType();
                ContentType ct = ContentType.create(contentTypeHeader);
                String fieldName = part.getFieldName();         // Corresponds to ServletAPI Part.getName
                String submittedFileName = part.getName();      // Corresponds to ServletAPI Part.getSubmittedFileName

                handlePart(action, input, base, ct, submittedFileName, countingDest, limits);
            }
        }
        catch (UploadLimitExceededException ex) { uploadTooLarge(ex.getMessage()); }
        catch (FileUploadSizeException ex)  { uploadTooLarge(uploadLimitMessage(ex)); }
        catch (ActionErrorException ex) { throw ex; }
        catch (RuntimeException ex)     { maybeUploadLimitExceeded(ex); throw ex; }
        catch (Exception ex)            { ServletOps.errorOccurred(ex.getMessage()); }
        // Overall results.
        UploadDetails details = new UploadDetails(countingDest.count(), countingDest.countTriples(),countingDest.countQuads());
        return details;
    }

    /**
     * Process one item in a multiple part file upload.
     * This does not depend on the choice of multi-part parser.
     */
    private static void handlePart(HttpAction action, InputStream input,
                                   String base, ContentType ct, String submittedFileName,
                                   StreamRDF dest, UploadLimits limits) throws IOException {
        input = limitInputStream(input, limits.maxFileSize, "Multipart upload file");

        Lang lang = null;
        if ( ! matchContentType(ctTextPlain, ct) )
            lang = RDFLanguages.contentTypeToLang(ct.getContentTypeStr());

        if ( lang == null ) {
            // Not a recognized Content-Type. Look at file extension.
            if ( submittedFileName == null || submittedFileName.equals("") )
                ServletOps.errorBadRequest("No name for content - can't determine RDF syntax");
            lang = RDFLanguages.pathnameToLang(submittedFileName);
            if (submittedFileName.endsWith(".gz"))
                input = new GZIPInputStream(input);
            else if (submittedFileName.endsWith(".bz2"))
                input = new BZip2CompressorInputStream(input, true);
        }
        if ( lang == null )
            // Desperate.
            lang = RDFLanguages.RDFXML;
        input = limitInputStream(input, limits.maxExpandedSize, "Expanded upload data");

        String printfilename = submittedFileName;
        if ( printfilename == null || printfilename.equals("") )
            printfilename = "<none>";

        // count just this step
        StreamRDFCounting countingDest2 =  StreamRDFLib.count(dest);
        try {
            ActionLib.parse(action, countingDest2, input, lang, base);
            UploadDetails details1 = new UploadDetails(countingDest2.count(), countingDest2.countTriples(),countingDest2.countQuads());
            action.log.info(format("[%d] Filename: %s, Content-Type=%s, Charset=%s => %s : %s",
                                   action.id, printfilename, ct.getContentTypeStr(), ct.getCharset(), lang.getName(),
                                   details1.detailsStr()));
        } catch (RiotParseException ex) {
            action.log.info(format("[%d] Filename: %s, Content-Type=%s, Charset=%s => %s : %s",
                                   action.id, printfilename, ct.getContentTypeStr(), ct.getCharset(), lang.getName(),
                                   ex.getMessage()));
            ActionLib.consumeBody(action);
            throw ex;
        }
    }

    private static void configureUploadLimits(JakartaServletFileUpload<?,?> upload, UploadLimits limits) {
        if ( isEnabled(limits.maxRequestSize) )
            upload.setMaxSize(limits.maxRequestSize);
        if ( isEnabled(limits.maxFiles) )
            upload.setMaxFileCount(limits.maxFiles);
    }

    private static void rejectIfContentLengthExceeds(HttpAction action, long limit) {
        long len = action.getRequestContentLengthLong();
        if ( isEnabled(limit) && len > limit )
            uploadTooLarge(format("Upload Content-Length %d exceeds configured limit %d", len, limit));
    }

    private static boolean isEnabled(long limit) {
        return limit >= 0;
    }

    private static InputStream limitInputStream(InputStream input, long limit, String description) {
        if ( ! isEnabled(limit) )
            return input;
        return new UploadLimitInputStream(input, limit, description);
    }

    private static UploadLimits uploadLimits() {
        return new UploadLimits(
            getLongProperty(SYSTEM_PROPERTY_MAX_REQUEST_SIZE, DEFAULT_MAX_REQUEST_SIZE),
            getLongProperty(SYSTEM_PROPERTY_MAX_FILE_SIZE, DEFAULT_MAX_FILE_SIZE),
            getLongProperty(SYSTEM_PROPERTY_MAX_FILES, DEFAULT_MAX_FILES),
            getLongProperty(SYSTEM_PROPERTY_MAX_EXPANDED_SIZE, DEFAULT_MAX_EXPANDED_SIZE)
        );
    }

    private static long getLongProperty(String property, long dftValue) {
        Long value = Long.getLong(property);
        return value == null ? dftValue : value;
    }

    private static void uploadTooLarge(String message) {
        ServletOps.error(HttpSC.PAYLOAD_TOO_LARGE_413, message);
    }

    private static void maybeUploadLimitExceeded(Throwable ex) {
        String message = uploadLimitMessage(ex);
        if ( message != null )
            uploadTooLarge(message);
    }

    private static String uploadLimitMessage(Throwable ex) {
        for ( Throwable cause = ex; cause != null; cause = cause.getCause() ) {
            if ( cause instanceof UploadLimitExceededException uploadLimitExceeded )
                return uploadLimitExceeded.getMessage();
            if ( cause instanceof FileUploadSizeException fileUploadSize )
                return format("Upload exceeds configured limit (permitted=%d, actual=%d)", fileUploadSize.getPermitted(), fileUploadSize.getActualSize());
        }
        return null;
    }

    private static record UploadLimits(long maxRequestSize, long maxFileSize, long maxFiles, long maxExpandedSize) {}

    private static class UploadLimitInputStream extends FilterInputStream {
        private final long limit;
        private final String description;
        private long count = 0;

        UploadLimitInputStream(InputStream input, long limit, String description) {
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
                throw new UploadLimitExceededException(format("%s exceeds configured limit %d", description, limit));
            count += bytes;
        }
    }

    private static class UploadLimitExceededException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UploadLimitExceededException(String message) {
            super(message);
        }
    }
}

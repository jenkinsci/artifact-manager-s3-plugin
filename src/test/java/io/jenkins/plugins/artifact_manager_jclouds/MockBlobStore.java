/*
 * The MIT License
 *
 * Copyright 2018 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package io.jenkins.plugins.artifact_manager_jclouds;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.io.IOUtils;
import org.apache.http.ConnectionClosedException;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.bootstrap.HttpServer;
import org.apache.http.impl.bootstrap.ServerBootstrap;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpRequestHandler;
import org.jclouds.ContextBuilder;
import org.jclouds.blobstore.BlobStore;
import org.jclouds.blobstore.BlobStoreContext;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.domain.StorageMetadata;

/**
 * A mock storage provider which keeps all blobs in memory.
 * Presigned “external” URLs are supported.
 * Allows tests to inject failures such as HTTP errors or hangs.
 */
public final class MockBlobStore extends BlobStoreProvider {

    private static final long serialVersionUID = 42L;
    private static final Logger LOGGER = Logger.getLogger(MockBlobStore.class.getName());

    private transient BlobStoreContext context;
    private transient URL baseURL;

    @Override
    public String getPrefix() {
        return "";
    }

    @Override
    public String getContainer() {
        return "container";
    }

    private static final Map<String, HttpRequestHandler> specialHandlers = new ConcurrentHashMap<>();

    /**
     * Requests that the <em>next</em> HTTP access to a particular presigned URL should behave specially.
     * @param method upload or download
     * @param key the blob’s {@link StorageMetadata#getName}
     * @param handler what to do instead
     */
    static void speciallyHandle(HttpMethod method, String key, HttpRequestHandler handler) {
        specialHandlers.put(method + ":" + key, handler);
    }

    /**
     * Parse URL query parameters from a URI string using Java stdlib
     */
    private static Map<String, String> parseQueryParams(String uri) {
        Map<String, String> params = new HashMap<>();
        try {
            URI parsedUri = new URI(uri);
            String query = parsedUri.getQuery();
            if (query != null) {
                for (String param : query.split("&")) {
                    String[] keyValue = param.split("=", 2);
                    if (keyValue.length == 2) {
                        String key = URLDecoder.decode(keyValue[0], StandardCharsets.UTF_8.name());
                        String value = URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8.name());
                        params.put(key, value);
                    }
                }
            }
        } catch (Exception e) {
            // Return empty map for malformed URIs
        }
        return params;
    }

    /**
     * Extract container and key from URI path using Java stdlib
     */
    private static String[] extractContainerAndKey(String uri) {
        try {
            URI parsedUri = new URI(uri);
            String path = parsedUri.getPath();
            
            // Remove leading slash
            if (path.startsWith("/")) {
                path = path.substring(1);
            }
            
            int firstSlash = path.indexOf('/');
            if (firstSlash == -1) {
                return null;
            }
            
            String container = path.substring(0, firstSlash);
            String key = path.substring(firstSlash + 1);
            return new String[]{container, key};
        } catch (URISyntaxException e) {
            return null;
        }
    }

    @Override
    public synchronized BlobStoreContext getContext() throws IOException {
        if (context == null) {
            context = ContextBuilder.newBuilder("mock").buildView(BlobStoreContext.class);
            HttpServer server = ServerBootstrap.bootstrap().
                registerHandler("*", (HttpRequest request, HttpResponse response, HttpContext _context) -> {
                    String method = request.getRequestLine().getMethod();
                    String uri = request.getRequestLine().getUri();
                    
                    Map<String, String> queryParams = parseQueryParams(uri);
                    String[] containerAndKey = extractContainerAndKey(uri);
                    String container = containerAndKey[0];
                    String key = containerAndKey[1];
                    
                    if (containerAndKey == null || !method.equals(queryParams.get("method"))) {
                        throw new IllegalStateException("Unexpected URI format: " + uri);
                    }
                        
                    HttpRequestHandler specialHandler = specialHandlers.remove(method + ":" + key);
                    if (specialHandler != null) {
                        specialHandler.handle(request, response, _context);
                        return;
                    }

                    // Handle multipart upload requests
                    if (queryParams.containsKey("uploadId") && queryParams.containsKey("partNumber")) {
                        String uploadId = queryParams.get("uploadId");
                        int partNumber = Integer.parseInt(queryParams.get("partNumber"));
                        
                        if ("PUT".equals(method)) {
                            HttpEntity entity = ((HttpEntityEnclosingRequest) request).getEntity();
                            byte[] data = IOUtils.toByteArray(entity.getContent());
                            
                            // Store the part data with a unique key
                            String partKey = uploadId + "-part-" + partNumber;
                            uploadedParts.put(partKey, data);
                            
                            response.setStatusCode(200);
                            response.setHeader("ETag", "\"mock-etag-" + uploadId + "-" + partNumber + "\"");
                            LOGGER.log(Level.INFO, "Uploaded part {0} ({1} bytes) for multipart upload {2} to {3}:{4}", 
                                new Object[]{partNumber, data.length, uploadId, container, key});
                            return;
                        } else {
                            throw new IllegalStateException();
                        }
                    }

                    BlobStore blobStore = context.getBlobStore();
                    switch (method) {
                        case "GET": {
                            Blob blob = blobStore.getBlob(container, key);
                            if (blob == null) {
                                response.setStatusCode(404);
                                return;
                            }
                            byte[] data = IOUtils.toByteArray(blob.getPayload().openStream());
                            response.setStatusCode(200);
                            response.setEntity(new ByteArrayEntity(data));
                            LOGGER.log(Level.INFO, "Serving {0} bytes from {1}:{2}", new Object[] {data.length, container, key});
                            return;
                        } case "PUT": {
                            HttpEntity entity = ((HttpEntityEnclosingRequest) request).getEntity();
                            byte[] data = IOUtils.toByteArray(entity.getContent());
                            Blob blob = blobStore.blobBuilder(key).payload(data).build();
                            if (!blobStore.containerExists(container)) {
                                blobStore.createContainerInLocation(null, container);
                            }
                            blobStore.putBlob(container, blob);
                            response.setStatusCode(204);
                            LOGGER.log(Level.INFO, "Uploaded {0} bytes to {1}:{2}", new Object[] {data.length, container, key});
                            return;
                        } default: {
                            throw new IllegalStateException();
                        }
                    }
                }).
                setExceptionLogger(x -> {
                    if (x instanceof ConnectionClosedException) {
                        LOGGER.info(x.toString());
                    } else {
                        LOGGER.log(Level.INFO, "error thrown in HTTP service", x);
                    }
                }).
                create();
            server.start();
            baseURL = new URL("http", server.getInetAddress().getHostName(), server.getLocalPort(), "/");
            LOGGER.log(Level.INFO, "Mock server running at {0}", baseURL);
        }
        return context;
    }

    @Override
    public URI toURI(String container, String key) {
        return URI.create("mock://" + container + "/" + key);
    }

    @Override
    public URL toExternalURL(Blob blob, HttpMethod httpMethod) throws IOException {
        return new URL(baseURL, blob.getMetadata().getContainer() + "/" + blob.getMetadata().getName() + "?method=" + httpMethod);
    }

    @Override
    public boolean isDeleteArtifacts() {
        return true;
    }

    @Override
    public boolean isDeleteStashes() {
        return true;
    }

    private static final AtomicInteger uploadIdCounter = new AtomicInteger(0);
    private static final Map<String, MockMultipartUpload> activeUploads = new ConcurrentHashMap<>();
    private static final Map<String, byte[]> uploadedParts = new ConcurrentHashMap<>();

    @Override
    public MultipartUploader initiateMultipartUpload(Blob blob) throws IOException {
        String uploadId = "upload-" + uploadIdCounter.incrementAndGet();
        MockMultipartUpload upload = new MockMultipartUpload(uploadId, blob, baseURL, context);
        activeUploads.put(uploadId, upload);
        return upload;
    }

    /**
     * Mock implementation of multipart upload
     */
    private static class MockMultipartUpload implements MultipartUploader {
        private final String uploadId;
        private final Blob blob;
        private final URL baseURL;
        private final BlobStoreContext context;
        private boolean completed = false;

        MockMultipartUpload(String uploadId, Blob blob, URL baseURL, BlobStoreContext context) {
            this.uploadId = uploadId;
            this.blob = blob;
            this.baseURL = baseURL;
            this.context = context;
        }

        @Override
        public URL toExternalURL(int partNumber) throws IOException {
            if (baseURL == null) {
                throw new IOException("Mock server not initialized");
            }
            // Generate URL for multipart upload part
            return new URL(baseURL, blob.getMetadata().getContainer() + "/" + 
                         blob.getMetadata().getName() + "?method=PUT&uploadId=" + uploadId + "&partNumber=" + partNumber);
        }

        @Override
        public void complete(List<Part> parts) throws IOException {
            if (completed) {
                throw new IOException("Upload already completed");
            }
            completed = true;
            activeUploads.remove(uploadId);
            
            // Combine all uploaded parts in order
            BlobStore blobStore = context.getBlobStore();
            String container = blob.getMetadata().getContainer();
            String key = blob.getMetadata().getName();
            
            // Calculate total size and combine parts
            int totalSize = 0;
            for (Part part : parts) {
                String partKey = uploadId + "-part-" + part.getPartNumber();
                byte[] partData = uploadedParts.get(partKey);
                if (partData != null) {
                    totalSize += partData.length;
                }
            }
            
            // Combine all parts into one byte array
            byte[] combinedData = new byte[totalSize];
            int offset = 0;
            for (Part part : parts) {
                String partKey = uploadId + "-part-" + part.getPartNumber();
                byte[] partData = uploadedParts.remove(partKey); // Remove after use
                if (partData != null) {
                    System.arraycopy(partData, 0, combinedData, offset, partData.length);
                    offset += partData.length;
                }
            }
            
            // Create and store the combined blob
            Blob combinedBlob = blobStore.blobBuilder(key).payload(combinedData).build();
            if (!blobStore.containerExists(container)) {
                blobStore.createContainerInLocation(null, container);
            }
            blobStore.putBlob(container, combinedBlob);
            
            LOGGER.log(Level.INFO, "Completed multipart upload {0} with {1} parts ({2} total bytes) for {3}:{4}", 
                new Object[]{uploadId, parts.size(), combinedData.length, container, key});
        }

        @Override
        public void close() throws Exception {
            if (!completed) {
                activeUploads.remove(uploadId);
                
                // Clean up any uploaded parts for this upload
                uploadedParts.entrySet().removeIf(entry -> entry.getKey().startsWith(uploadId + "-part-"));
                
                LOGGER.log(Level.INFO, "Aborted multipart upload {0} for {1}:{2}", 
                    new Object[]{uploadId, blob.getMetadata().getContainer(), blob.getMetadata().getName()});
            }
        }
    }

}

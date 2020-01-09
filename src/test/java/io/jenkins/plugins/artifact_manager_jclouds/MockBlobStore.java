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
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    @Override
    public synchronized BlobStoreContext getContext() throws IOException {
        if (context == null) {
            context = ContextBuilder.newBuilder("mock").buildView(BlobStoreContext.class);
            HttpServer server = ServerBootstrap.bootstrap().
                registerHandler("*", (HttpRequest request, HttpResponse response, HttpContext _context) -> {
                    String method = request.getRequestLine().getMethod();
                    Matcher m = Pattern.compile("/([^/]+)/(.+)[?]method=" + method).matcher(request.getRequestLine().getUri());
                    if (!m.matches()) {
                        throw new IllegalStateException();
                    }
                    String container = m.group(1);
                    String key = m.group(2);
                    HttpRequestHandler specialHandler = specialHandlers.remove(method + ":" + key);
                    if (specialHandler != null) {
                        specialHandler.handle(request, response, _context);
                        return;
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
            baseURL = new URL("http://" + server.getInetAddress().getHostName() + ":" + server.getLocalPort() + "/");
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

}

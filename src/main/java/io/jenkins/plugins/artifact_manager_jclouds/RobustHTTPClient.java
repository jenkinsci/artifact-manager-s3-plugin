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

import hudson.AbortException;
import hudson.model.Computer;
import hudson.model.TaskListener;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.net.URL;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import jenkins.security.MasterToSlaveCallable;
import jenkins.util.JenkinsJVM;
import org.apache.commons.io.IOUtils;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.FileEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.Beta;

/**
 * Utility to make HTTP connections with protection against transient failures.
 */
@Restricted(Beta.class)
public final class RobustHTTPClient implements Serializable {

    private static final long serialVersionUID = 1;

    private static final ExecutorService executors = JenkinsJVM.isJenkinsJVM() ? Computer.threadPoolForRemoting : Executors.newCachedThreadPool();

    private int stopAfterAttemptNumber;
    private long waitMultiplier;
    private long waitMaximum;
    private long timeout;

    /**
     * Creates a client configured with reasonable defaults from system properties.
     * <p>This constructor should be run in the Jenkins master.
     * To make requests from an agent JVM, create a {@code final} field of this type in your {@link MasterToSlaveCallable} or similar;
     * set it with a field initializer (run in the callable’s constructor on the master),
     * letting the agent deserialize the configuration.
     */
    public RobustHTTPClient() {
        JenkinsJVM.checkJenkinsJVM();
        this.stopAfterAttemptNumber = Integer.getInteger(RobustHTTPClient.class.getName() + ".STOP_AFTER_ATTEMPT_NUMBER", 10);
        this.waitMultiplier = Long.getLong(RobustHTTPClient.class.getName() + ".WAIT_MULTIPLIER", 100);
        this.waitMaximum = Long.getLong(RobustHTTPClient.class.getName() + ".WAIT_MAXIMUM", 300);
        this.timeout = Long.getLong(RobustHTTPClient.class.getName() + ".TIMEOUT", /* 15m */15 * 60);
    }

    /**
     * Number of upload/download attempts of nonfatal errors before giving up.
     */
    public void setStopAfterAttemptNumber(int stopAfterAttemptNumber) {
        this.stopAfterAttemptNumber = stopAfterAttemptNumber;
    }

    /**
     * Initial number of milliseconds between first and second upload/download attempts.
     * Subsequent ones increase exponentially.
     * Note that this is not a <em>randomized</em> exponential backoff;
     * and the base of the exponent is currently hard-coded to 2.
     */
    public void setWaitMultiplier(long waitMultiplier) {
        this.waitMultiplier = waitMultiplier;
    }

    /**
     * Maximum number of seconds between upload/download attempts.
     */
    public void setWaitMaximum(long waitMaximum) {
        this.waitMaximum = waitMaximum;
    }

    /**
     * Number of seconds to permit a single upload/download attempt to take.
     */
    public void setTimeout(long timeout) {
        this.timeout = timeout;
    }

    @FunctionalInterface
    public interface ConnectionCreator {
        CloseableHttpResponse connect(CloseableHttpClient client) throws IOException, InterruptedException;
    }

    @FunctionalInterface
    public interface ConnectionUser {
        void use(CloseableHttpResponse response) throws IOException, InterruptedException;
    }

    /**
     * Perform an HTTP network operation with appropriate timeouts and retries.
     * @param whatConcise a short description of the operation, like {@code upload}
     * @param whatVerbose a longer description of the operation, like {@code uploading … to …}
     * @param connectionCreator how to establish a connection prior to getting the server’s response
     * @param connectionUser what to do, if anything, after a successful (2xx) server response
     * @param listener a place to print messages
     * @throws IOException if there is an unrecoverable error; {@link AbortException} will be used where appropriate
     * @throws InterruptedException if the transfer is interrupted
     */
    public void connect(String whatConcise, String whatVerbose, ConnectionCreator connectionCreator, ConnectionUser connectionUser, TaskListener listener) throws IOException, InterruptedException {
        AtomicInteger responseCode = new AtomicInteger();
        int attempt = 1;
        while (true) {
            try {
                try {
                    executors.submit(() -> {
                        responseCode.set(0);
                        try (CloseableHttpClient client = HttpClients.createSystem()) {
                            try (CloseableHttpResponse response = connectionCreator.connect(client)) {
                                StatusLine statusLine = response.getStatusLine();
                                responseCode.set(statusLine != null ? statusLine.getStatusCode() : 0);
                                if (responseCode.get() < 200 || responseCode.get() >= 300) {
                                    String diag;
                                    HttpEntity entity = response.getEntity();
                                    if (entity != null) {
                                        try (InputStream err = entity.getContent()) {
                                            Header contentEncoding = entity.getContentEncoding();
                                            diag = IOUtils.toString(err, contentEncoding != null ? contentEncoding.getValue() : null);
                                        }
                                    } else {
                                        diag = null;
                                    }
                                    throw new AbortException(String.format("Failed to %s, response: %d %s, body: %s", whatVerbose, responseCode.get(), statusLine != null ? statusLine.getReasonPhrase() : "?", diag));
                                }
                                connectionUser.use(response);
                            }
                        }
                        return null; // success
                    }).get(timeout, TimeUnit.SECONDS);
                } catch (TimeoutException x) {
                    throw new ExecutionException(new IOException(x)); // ExecutionException unwrapped & treated as retryable below
                }
                listener.getLogger().flush(); // seems we can get interleaved output with master otherwise
                return; // success
            } catch (ExecutionException wrapped) {
                Throwable x = wrapped.getCause();
                if (x instanceof IOException) {
                    if (attempt == stopAfterAttemptNumber) {
                        throw (IOException) x; // last chance
                    }
                    if (responseCode.get() > 0 && responseCode.get() < 200 || responseCode.get() >= 300 && responseCode.get() < 500) {
                        throw (IOException) x; // 4xx errors should not be retried
                    }
                    // TODO exponent base (2) could be made into a configurable parameter
                    Thread.sleep(Math.min(((long) Math.pow(2, attempt)) * waitMultiplier, waitMaximum * 1000));
                    listener.getLogger().printf("Retrying %s after: %s%n", whatConcise, x instanceof AbortException ? x.getMessage() : x.toString());
                    attempt++; // and continue
                } else if (x instanceof InterruptedException) { // all other exceptions considered fatal
                    throw (InterruptedException) x;
                } else if (x instanceof RuntimeException) {
                    throw (RuntimeException) x;
                } else if (x != null) {
                    throw new RuntimeException(x);
                } else {
                    throw new IllegalStateException();
                }
            }
        }
    }

    /**
     * Upload a file to a URL.
     */
    public void uploadFile(File f, URL url, TaskListener listener) throws IOException, InterruptedException {
        connect("upload", "upload " + f + " to " + url.toString().replaceFirst("[?].+$", "?…"), client -> {
            HttpPut put = new HttpPut(url.toString());
            put.setEntity(new FileEntity(f));
            return client.execute(put);
        }, response -> {}, listener);
    }

}

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
package io.jenkins.plugins.artifact_manager_jclouds.s3;

import com.cloudbees.jenkins.plugins.awscredentials.AWSCredentialsImpl;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.domains.Domain;
import io.jenkins.plugins.aws.global_configuration.CredentialsAwsGlobalConfiguration;
import jenkins.model.Jenkins;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.testcontainers.containers.MinIOContainer;
import org.junit.Before;

public class MinioIntegrationTest extends AbstractIntegrationTest {
    private static final String REGION = "us-east-1";
    
    private static MinIOContainer minioServer;

    @BeforeClass
    public static void setUpClass() throws Exception {
        minioServer = new MinIOContainer("minio/minio");
        minioServer.start();
    }
    
    @AfterClass
    public static void shutDownClass() {
        if (minioServer != null && minioServer.isRunning()) {
            minioServer.stop();
        }
    }

    @Before public void configure() throws Throwable {
        rr.startJenkins();
        var endpoint = minioServer.getS3URL().replaceFirst("^http://", "");
        var username = minioServer.getUserName();
        var password = minioServer.getPassword();
        rr.run(r -> {
            CredentialsAwsGlobalConfiguration credentialsConfig = CredentialsAwsGlobalConfiguration.get();
            credentialsConfig.setRegion(REGION);
            CredentialsProvider.lookupStores(Jenkins.get())
                    .iterator()
                    .next()
                    .addCredentials(Domain.global(), new AWSCredentialsImpl(CredentialsScope.GLOBAL, "MinioIntegrationTest", username, password, null));
            credentialsConfig.setCredentialsId("MinioIntegrationTest");

            var config = S3BlobStoreConfig.get();
            config.setContainer(CONTAINER_NAME);
            config.setPrefix(CONTAINER_PREFIX);
            config.setCustomEndpoint(endpoint);
            config.setCustomSigningRegion(REGION);
            config.setUseHttp(true);
            config.setUsePathStyleUrl(true);
            config.setDisableSessionToken(true);
        });
    }

}

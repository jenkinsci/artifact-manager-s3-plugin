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
import org.apache.commons.lang.StringUtils;
import org.jclouds.aws.domain.Region;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

import org.testcontainers.Testcontainers;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Locale;

import static org.testcontainers.containers.localstack.LocalStackContainer.Service.S3;

class LocalStackIntegrationTest extends AbstractIntegrationTest {

    private static LocalStackContainer localstack;

    @BeforeAll
    static void beforeAll() {
        localstack = new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.4.0"))
                .withServices(S3);
        localstack.start();
        Integer mappedPort = localstack.getFirstMappedPort();
        Testcontainers.exposeHostPorts(mappedPort);
    }

    @AfterAll
    static void afterAll() {
        if (localstack != null && localstack.isRunning()) {
            localstack.stop();
        }
    }

    @BeforeEach
    void beforeEach() throws Throwable {
        rr.startJenkins();
        var endpoint = localstack.getEndpoint().getHost() + ":" + localstack.getEndpoint().getPort();
        var username = localstack.getAccessKey();
        var password = localstack.getSecretKey();
        var region = localstack.getRegion();
        rr.run(r -> {
            CredentialsAwsGlobalConfiguration credentialsConfig = CredentialsAwsGlobalConfiguration.get();
            credentialsConfig.setRegion(region);
            CredentialsProvider.lookupStores(Jenkins.get())
                    .iterator()
                    .next()
                    .addCredentials(Domain.global(), new AWSCredentialsImpl(CredentialsScope.GLOBAL, "LocalStackIntegrationTest", username, password, null));
            credentialsConfig.setCredentialsId("LocalStackIntegrationTest");

            var config = S3BlobStoreConfig.get();
            config.setContainer(CONTAINER_NAME);
            config.setPrefix(CONTAINER_PREFIX);
            config.setCustomEndpoint(endpoint);
            config.setUseHttp(true);
            config.setUsePathStyleUrl(true);
            config.setDisableSessionToken(true);
            config.setCustomSigningRegion(StringUtils.isBlank(region) ? Region.US_EAST_1.toLowerCase(Locale.US) : region);
        });
    }
}

/*
 * The MIT License
 *
 * Copyright 2023 CloudBees, Inc.
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

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;

/**
 * Allows use of {@code aws sso login} when running tests.
 * Adapted from https://github.com/aws/aws-sdk-java/issues/2434#issuecomment-819985174 and https://github.com/aws/aws-sdk-java/issues/803#issuecomment-593530484
 */
public class V2ProfileCredentialsProvider implements AwsCredentialsProvider {

    private final ProfileCredentialsProvider delegate = ProfileCredentialsProvider.create();

    @Override
    public AwsCredentials resolveCredentials() {
        AwsCredentials credentials = delegate.resolveCredentials();
        if (credentials instanceof AwsSessionCredentials) {
            AwsSessionCredentials sessionCredentials = (AwsSessionCredentials) credentials;
            return AwsSessionCredentials.builder().accessKeyId(sessionCredentials.accessKeyId()).secretAccessKey(sessionCredentials.secretAccessKey()).sessionToken(sessionCredentials.sessionToken()).build();
        } else {
            return AwsBasicCredentials.builder().accessKeyId(credentials.accessKeyId()).secretAccessKey(credentials.secretAccessKey()).build();
        }
    }

}

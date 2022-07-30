package io.jenkins.plugins.artifact_manager_jclouds;

import org.apache.tika.Tika;

import java.io.File;
import java.io.IOException;

public class TikaUtil {

    private static Tika tika = new Tika();

    static String detectByTika(File f) throws IOException {
        return tika.detect(f);
    }
}

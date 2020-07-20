package io.jenkins.plugins.artifact_manager_jclouds;

import org.apache.commons.io.input.BoundedInputStream;
import org.apache.http.entity.AbstractHttpEntity;
import org.apache.http.util.Args;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class FilePartEntity extends AbstractHttpEntity implements Cloneable {
    protected final File file;
    protected final long offset;
    protected final long limit;

    public FilePartEntity(File file, long offset, long limit) {
        this.file = (File) Args.notNull(file, "File");
        this.offset = offset;
        this.limit = limit;
    }

    public boolean isRepeatable() {
        return true;
    }

    public long getContentLength() {
        return this.limit;
    }

    public InputStream getContent() throws IOException {
        FileInputStream stream = new FileInputStream(this.file);
        long skip = -1;
        try {
            skip = stream.skip(this.offset);
        } finally {
            if ( skip != this.offset) {
                stream.close();
                throw new EOFException(this.file.getPath());
            }
        }
        return new BoundedInputStream(stream, this.limit);
    }

    public void writeTo(OutputStream outstream) throws IOException {
        Args.notNull(outstream, "Output stream");
        try (InputStream instream = getContent()) {
            byte[] tmp = new byte[4096];

            int l;
            while ((l = instream.read(tmp)) != -1) {
                outstream.write(tmp, 0, l);
            }

            outstream.flush();
        }
    }

    public boolean isStreaming() {
        return false;
    }

    public Object clone() throws CloneNotSupportedException {
        return super.clone();
    }
}

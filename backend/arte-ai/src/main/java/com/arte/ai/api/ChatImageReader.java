package com.arte.ai.api;

import java.io.IOException;

/** Reads images from the application's configured file storage. */
public interface ChatImageReader {
    byte[] readImage(String url) throws IOException;
}

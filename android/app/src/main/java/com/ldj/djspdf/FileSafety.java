package com.ldj.djspdf;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Locale;

/** Pure Java boundaries shared by the bridge, provider and updater. */
public final class FileSafety {
    public static final long MAX_BYTES = 256L * 1024 * 1024;
    private FileSafety() {}

    public static File within(File root, File candidate) throws IOException {
        File base = root.getCanonicalFile();
        File file = candidate.getCanonicalFile();
        if (!file.getPath().startsWith(base.getPath() + File.separator)) {
            throw new IOException("허용되지 않은 파일 경로");
        }
        return file;
    }

    public static String pdfName(String value) {
        String name = value == null ? "문서" : value.replaceAll("[\\x00-\\x1f\\x7f/\\\\]", "_").trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) name = "문서";
        if (name.length() > 120) name = name.substring(0, 120);
        return name.toLowerCase(Locale.ROOT).endsWith(".pdf") ? name : name + ".pdf";
    }

    public static boolean apkUrl(String value) {
        try {
            URI uri = new URI(value);
            String path = uri.getRawPath();
            return "https".equals(uri.getScheme()) && "github.com".equals(uri.getHost())
                    && (uri.getPort() == -1 || uri.getPort() == 443) && uri.getUserInfo() == null
                    && uri.getRawQuery() == null && uri.getRawFragment() == null && path != null
                    && path.matches("/ldjkorea/DJS-PDF/releases/download/v[0-9][A-Za-z0-9._-]*/[A-Za-z0-9._-]+\\.apk")
                    && !path.contains("..");
        } catch (Exception ignored) { return false; }
    }
}

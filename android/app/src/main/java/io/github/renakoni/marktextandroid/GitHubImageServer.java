package io.github.renakoni.marktextandroid;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Serves repository images referenced by the open GitHub document, pure JVM
 * so it stays testable; {@link GitHubImageWebViewClient} adapts it to the
 * WebView. The web layer rewrites relative image paths to
 * {@code /_marktext_github_/<location>} on the app origin, and the native
 * side fetches them with the signed-in token — private repositories work
 * and the token never reaches web content.
 *
 * Only the repository and branch of the document currently open (the
 * context the web layer sets) are served, so a Markdown file cannot use the
 * endpoint to pull files from other repositories the account can read.
 *
 * The cache is keyed by branch name, which moves: a branch's entries are
 * dropped whenever its document is (re)opened or committed to, and when
 * the context switches to another branch.
 */
final class GitHubImageServer {

    static final String PATH_PREFIX = "/_marktext_github_/";
    static final int MAX_IMAGE_BYTES = 15 * 1024 * 1024;
    static final int MAX_CACHE_BYTES = 24 * 1024 * 1024;
    /**
     * Images are inert data: SVG served from the app origin must never run
     * script, even if something navigates to it directly.
     */
    static final String CONTENT_SECURITY_POLICY = "default-src 'none'; style-src 'unsafe-inline'; img-src data:";

    interface TokenSource {
        String accessToken() throws IOException, CloudProviderException;
    }

    static final class Response {

        final int status;
        final String reason;
        final String mimeType;
        final byte[] body;

        Response(int status, String reason, String mimeType, byte[] body) {
            this.status = status;
            this.reason = reason;
            this.mimeType = mimeType;
            this.body = body;
        }
    }

    private final GitHubClient client;
    private final TokenSource tokens;
    private final Object lock = new Object();
    private GitHubClient.Location context;
    private int cachedBytes = 0;
    /** Bumped by every invalidation; a fetch that straddles one is not cached. */
    private long generation = 0;
    /** Access-ordered LRU, bounded by total bytes rather than entries. */
    private final LinkedHashMap<String, byte[]> cache = new LinkedHashMap<>(16, 0.75f, true);

    GitHubImageServer(GitHubClient client, TokenSource tokens) {
        this.client = client;
        this.tokens = tokens;
    }

    /** The open document's location, or null when no GitHub document is open. */
    void setContext(GitHubClient.Location documentLocation) {
        synchronized (lock) {
            if (context != null && !sameBranch(context, documentLocation)) {
                dropBranch(context);
            }
            context = documentLocation;
        }
    }

    /**
     * Drops cached images of the location's repository and branch, after it
     * was committed to or its document was read afresh.
     */
    void invalidate(GitHubClient.Location branch) {
        synchronized (lock) {
            dropBranch(branch);
        }
    }

    /** Drops cached bytes, e.g. after sign-out. */
    void clear() {
        synchronized (lock) {
            context = null;
            cache.clear();
            cachedBytes = 0;
            generation++;
        }
    }

    static boolean handles(String encodedPath) {
        return encodedPath != null && encodedPath.startsWith(PATH_PREFIX);
    }

    /** @param encodedPath the request's still percent-encoded URL path */
    Response serve(String encodedPath) {
        GitHubClient.Location location;
        try {
            location = GitHubClient.Location.parse(encodedPath.substring(PATH_PREFIX.length()));
        } catch (CloudProviderException ex) {
            return error(400, "Bad Request");
        }
        if (location.ref == null || location.path.length() == 0) {
            return error(400, "Bad Request");
        }
        String mimeType = imageMimeType(location.path);
        if (mimeType == null) {
            return error(415, "Unsupported Media Type");
        }

        String key = location.serialize();
        long fetchGeneration;
        synchronized (lock) {
            if (!isInContext(location)) {
                return error(403, "Forbidden");
            }
            byte[] cached = cache.get(key);
            if (cached != null) {
                return new Response(200, "OK", mimeType, cached);
            }
            fetchGeneration = generation;
        }

        byte[] bytes;
        try {
            bytes = client.readRaw(tokens.accessToken(), location, MAX_IMAGE_BYTES);
        } catch (CloudProviderException ex) {
            if ("CLOUD_DOCUMENT_NOT_FOUND".equals(ex.code)) {
                return error(404, "Not Found");
            }
            if ("CLOUD_ACCESS_DENIED".equals(ex.code) || "CLOUD_SSO_REQUIRED".equals(ex.code)) {
                return error(403, "Forbidden");
            }
            return error(502, "Bad Gateway");
        } catch (IOException ex) {
            return error(502, "Bad Gateway");
        }

        synchronized (lock) {
            if (fetchGeneration == generation) {
                remember(key, bytes);
            }
        }
        return new Response(200, "OK", mimeType, bytes);
    }

    private boolean isInContext(GitHubClient.Location location) {
        return context != null && context.ref != null && sameBranch(context, location);
    }

    private static boolean sameBranch(GitHubClient.Location left, GitHubClient.Location right) {
        return right != null
            && left.owner.equalsIgnoreCase(right.owner)
            && left.repo.equalsIgnoreCase(right.repo)
            && left.ref != null
            && left.ref.equals(right.ref);
    }

    private void dropBranch(GitHubClient.Location branch) {
        generation++;
        Iterator<Map.Entry<String, byte[]>> entries = cache.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<String, byte[]> entry = entries.next();
            GitHubClient.Location cached;
            try {
                cached = GitHubClient.Location.parse(entry.getKey());
            } catch (CloudProviderException ex) {
                cached = null;
            }
            if (cached == null || sameBranch(branch, cached)) {
                cachedBytes -= entry.getValue().length;
                entries.remove();
            }
        }
    }

    private void remember(String key, byte[] bytes) {
        if (bytes.length > MAX_CACHE_BYTES / 4) {
            return;
        }
        byte[] previous = cache.put(key, bytes);
        if (previous != null) {
            cachedBytes -= previous.length;
        }
        cachedBytes += bytes.length;
        Iterator<Map.Entry<String, byte[]>> eldest = cache.entrySet().iterator();
        while (cachedBytes > MAX_CACHE_BYTES && eldest.hasNext()) {
            cachedBytes -= eldest.next().getValue().length;
            eldest.remove();
        }
    }

    static String imageMimeType(String path) {
        String lower = path.toLowerCase(Locale.US);
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (lower.endsWith(".avif")) {
            return "image/avif";
        }
        if (lower.endsWith(".bmp")) {
            return "image/bmp";
        }
        if (lower.endsWith(".ico")) {
            return "image/x-icon";
        }
        return null;
    }

    private static Response error(int status, String reason) {
        return new Response(status, reason, "text/plain", new byte[0]);
    }
}

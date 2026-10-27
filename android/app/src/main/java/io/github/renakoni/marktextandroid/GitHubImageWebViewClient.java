package io.github.renakoni.marktextandroid;

import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeWebViewClient;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Capacitor's WebView client plus the GitHub image endpoint: requests for
 * {@link GitHubImageServer#PATH_PREFIX} on the app's own origin are answered
 * by {@link GitHubImageServer}; everything else falls through to the bridge.
 * shouldInterceptRequest runs off the main thread, so the blocking GitHub
 * fetch is allowed here.
 */
final class GitHubImageWebViewClient extends BridgeWebViewClient {

    private final Bridge bridge;
    private final GitHubImageServer server;

    GitHubImageWebViewClient(Bridge bridge, GitHubImageServer server) {
        super(bridge);
        this.bridge = bridge;
        this.server = server;
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        Uri url = request.getUrl();
        if (
            !"GET".equalsIgnoreCase(request.getMethod())
            || !GitHubImageServer.handles(url.getEncodedPath())
            || url.getHost() == null
            || !url.getHost().equalsIgnoreCase(bridge.getHost())
        ) {
            return super.shouldInterceptRequest(view, request);
        }

        GitHubImageServer.Response response = server.serve(url.getEncodedPath());
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Security-Policy", GitHubImageServer.CONTENT_SECURITY_POLICY);
        headers.put("X-Content-Type-Options", "nosniff");
        headers.put("Cache-Control", "no-store");
        return new WebResourceResponse(
            response.mimeType,
            null,
            response.status,
            response.reason,
            headers,
            new ByteArrayInputStream(response.body)
        );
    }
}

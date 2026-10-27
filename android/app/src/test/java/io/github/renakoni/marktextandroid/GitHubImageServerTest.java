package io.github.renakoni.marktextandroid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.Test;

public class GitHubImageServerTest {

    /** Answers every request with the same body, recording the URLs. */
    private static final class FakeTransport implements HttpTransport {

        final List<Request> requests = new ArrayList<>();
        int status = 200;
        byte[] body = "PNG".getBytes(StandardCharsets.UTF_8);

        @Override
        public Response execute(Request request) {
            requests.add(request);
            return new Response(status, new LinkedHashMap<>(), body);
        }
    }

    private static GitHubImageServer server(FakeTransport transport) {
        return new GitHubImageServer(new GitHubClient(transport), () -> "TOKEN");
    }

    private static GitHubClient.Location document(String id) throws CloudProviderException {
        return GitHubClient.Location.parse(id);
    }

    @Test
    public void servesImagesOfTheOpenDocumentsBranch() throws Exception {
        FakeTransport transport = new FakeTransport();
        GitHubImageServer server = server(transport);
        server.setContext(document("octocat/hello/feature%2Fx/docs%2FREADME.md"));

        GitHubImageServer.Response response =
            server.serve("/_marktext_github_/octocat/hello/feature%2Fx/docs%2Fimg%2Fa%20b.png");

        assertEquals(200, response.status);
        assertEquals("image/png", response.mimeType);
        assertEquals("PNG", new String(response.body, StandardCharsets.UTF_8));
        HttpTransport.Request request = transport.requests.get(0);
        assertEquals(
            "https://api.github.com/repos/octocat/hello/contents/docs/img/a%20b.png?ref=feature%2Fx",
            request.url
        );
        assertEquals("Bearer TOKEN", request.headers.get("Authorization"));
        assertEquals(GitHubImageServer.MAX_IMAGE_BYTES, request.maxResponseBytes);
    }

    @Test
    public void cachesRepeatedImages() throws Exception {
        FakeTransport transport = new FakeTransport();
        GitHubImageServer server = server(transport);
        server.setContext(document("o/r/main/README.md"));

        server.serve("/_marktext_github_/o/r/main/a.png");
        server.serve("/_marktext_github_/o/r/main/a.png");

        assertEquals(1, transport.requests.size());
    }

    @Test
    public void refusesOtherRepositoriesBranchesAndNoContext() throws Exception {
        FakeTransport transport = new FakeTransport();
        GitHubImageServer server = server(transport);

        assertEquals(403, server.serve("/_marktext_github_/o/r/main/a.png").status);

        server.setContext(document("o/r/main/README.md"));
        assertEquals(403, server.serve("/_marktext_github_/o/private/main/a.png").status);
        assertEquals(403, server.serve("/_marktext_github_/o/r/dev/a.png").status);

        server.setContext(null);
        assertEquals(403, server.serve("/_marktext_github_/o/r/main/a.png").status);
        assertTrue(transport.requests.isEmpty());
    }

    @Test
    public void refusesNonImagesAndMalformedPaths() throws Exception {
        FakeTransport transport = new FakeTransport();
        GitHubImageServer server = server(transport);
        server.setContext(document("o/r/main/README.md"));

        assertEquals(415, server.serve("/_marktext_github_/o/r/main/secrets.env").status);
        assertEquals(400, server.serve("/_marktext_github_/o/r/main/..%2Fa.png").status);
        assertEquals(400, server.serve("/_marktext_github_/o/r").status);
        assertTrue(transport.requests.isEmpty());
    }

    @Test
    public void mapsFetchFailures() throws Exception {
        FakeTransport transport = new FakeTransport();
        GitHubImageServer server = server(transport);
        server.setContext(document("o/r/main/README.md"));

        transport.status = 404;
        assertEquals(404, server.serve("/_marktext_github_/o/r/main/missing.png").status);
        transport.status = 500;
        assertEquals(502, server.serve("/_marktext_github_/o/r/main/broken.png").status);
    }

    @Test
    public void clearDropsTheContextAndCache() throws Exception {
        FakeTransport transport = new FakeTransport();
        GitHubImageServer server = server(transport);
        server.setContext(document("o/r/main/README.md"));
        server.serve("/_marktext_github_/o/r/main/a.png");

        server.clear();
        assertEquals(403, server.serve("/_marktext_github_/o/r/main/a.png").status);
        server.setContext(document("o/r/main/README.md"));
        server.serve("/_marktext_github_/o/r/main/a.png");

        assertEquals(2, transport.requests.size());
    }

    @Test
    public void recognizesItsPathPrefixAndImageTypes() {
        assertTrue(GitHubImageServer.handles("/_marktext_github_/o/r/main/a.png"));
        assertFalse(GitHubImageServer.handles("/index.html"));
        assertFalse(GitHubImageServer.handles(null));
        assertEquals("image/svg+xml", GitHubImageServer.imageMimeType("logo.SVG"));
        assertEquals("image/jpeg", GitHubImageServer.imageMimeType("a.jpeg"));
        assertNull(GitHubImageServer.imageMimeType("a.md"));
    }
}

package io.github.renakoni.marktextandroid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class GitHubClientTest {

    /** Canned transport recording requests and replaying queued responses. */
    private static final class FakeTransport implements HttpTransport {

        final List<Request> requests = new ArrayList<>();
        final Deque<Response> responses = new ArrayDeque<>();

        void enqueue(int status, String body) {
            enqueue(status, new LinkedHashMap<>(), body);
        }

        void enqueue(int status, Map<String, String> headers, String body) {
            responses.addLast(new Response(
                status,
                headers,
                body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8)
            ));
        }

        @Override
        public Response execute(Request request) {
            requests.add(request);
            if (responses.isEmpty()) {
                throw new IllegalStateException("No canned response for " + request.url);
            }
            return responses.removeFirst();
        }
    }

    private static String bodyOf(HttpTransport.Request request) {
        return new String(request.body, StandardCharsets.UTF_8);
    }

    private static void assertFailsWith(String code, ThrowingRunnable runnable) throws Exception {
        try {
            runnable.run();
            fail("Expected " + code);
        } catch (CloudProviderException ex) {
            assertEquals(code, ex.code);
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Test
    public void deviceCodeRequestCarriesClientIdAndScope() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(200, "{\"device_code\":\"DC\",\"user_code\":\"ABCD-1234\","
            + "\"verification_uri\":\"https://github.com/login/device\",\"expires_in\":900,\"interval\":5}");

        GitHubClient.DeviceCode code = new GitHubClient(transport).requestDeviceCode("client-1");

        assertEquals("DC", code.deviceCode);
        assertEquals("ABCD-1234", code.userCode);
        assertEquals("https://github.com/login/device", code.verificationUri);
        assertEquals(900, code.expiresInSeconds);
        assertEquals(5, code.intervalSeconds);
        HttpTransport.Request request = transport.requests.get(0);
        assertEquals("POST", request.method);
        assertEquals("https://github.com/login/device/code", request.url);
        assertEquals("application/json", request.headers.get("Accept"));
        assertEquals("client_id=client-1&scope=repo", bodyOf(request));
    }

    @Test
    public void deviceCodeRequestReportsADisabledDeviceFlow() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(200, "{\"error\":\"device_flow_disabled\"}");

        assertFailsWith("CLOUD_CLIENT_ID_MISSING",
            () -> new GitHubClient(transport).requestDeviceCode("client-1"));
    }

    @Test
    public void deviceCodeRequestRejectsANonHttpsVerificationUri() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(200, "{\"device_code\":\"DC\",\"user_code\":\"U\","
            + "\"verification_uri\":\"javascript:alert(1)\"}");

        assertFailsWith("CLOUD_AUTH_FAILED",
            () -> new GitHubClient(transport).requestDeviceCode("client-1"));
    }

    @Test
    public void pollingKeepsWaitingWhileAuthorizationIsPending() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(200, "{\"error\":\"authorization_pending\"}");

        GitHubClient.PollResult result =
            new GitHubClient(transport).pollDeviceToken("client-1", "DC", 5, 0L);

        assertTrue(result.isPending());
        assertEquals(5, result.nextIntervalSeconds);
        String form = bodyOf(transport.requests.get(0));
        assertTrue(form.contains("device_code=DC"));
        assertTrue(form.contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code"));
        assertEquals("https://github.com/login/oauth/access_token", transport.requests.get(0).url);
    }

    @Test
    public void slowDownWidensTheInterval() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(200, "{\"error\":\"slow_down\"}");
        transport.enqueue(200, "{\"error\":\"slow_down\",\"interval\":20}");
        GitHubClient client = new GitHubClient(transport);

        assertEquals(10, client.pollDeviceToken("c", "DC", 5, 0L).nextIntervalSeconds);
        assertEquals(20, client.pollDeviceToken("c", "DC", 10, 0L).nextIntervalSeconds);
    }

    @Test
    public void pollingMapsTerminalErrors() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(200, "{\"error\":\"access_denied\"}");
        transport.enqueue(200, "{\"error\":\"expired_token\"}");
        transport.enqueue(200, "{\"error\":\"incorrect_device_code\"}");
        GitHubClient client = new GitHubClient(transport);

        assertFailsWith("CLOUD_AUTH_CANCELED", () -> client.pollDeviceToken("c", "DC", 5, 0L));
        assertFailsWith("CLOUD_AUTH_EXPIRED", () -> client.pollDeviceToken("c", "DC", 5, 0L));
        assertFailsWith("CLOUD_AUTH_FAILED", () -> client.pollDeviceToken("c", "DC", 5, 0L));
    }

    @Test
    public void pollingTreatsServerErrorsAsTransient() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(502, "<html>bad gateway</html>");

        GitHubClient.PollResult result = new GitHubClient(transport).pollDeviceToken("c", "DC", 5, 0L);

        assertTrue(result.isPending());
        assertEquals(5, result.nextIntervalSeconds);
    }

    @Test
    public void oauthAppTokensNeverExpire() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(200, "{\"access_token\":\"gho_1\",\"token_type\":\"bearer\",\"scope\":\"repo\"}");

        GitHubClient.PollResult result = new GitHubClient(transport).pollDeviceToken("c", "DC", 5, 1_000L);

        assertFalse(result.isPending());
        assertEquals("gho_1", result.tokens.accessToken);
        assertEquals("", result.tokens.refreshToken);
        assertEquals(CloudTokenStore.NEVER_EXPIRES, result.tokens.expiresAtMillis);
        assertTrue(result.tokens.isAccessTokenFresh(Long.MAX_VALUE / 2));
    }

    @Test
    public void expiringAppTokensKeepTheirExpiryAndRefreshToken() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(200, "{\"access_token\":\"ghu_1\",\"expires_in\":28800,"
            + "\"refresh_token\":\"ghr_1\",\"refresh_token_expires_in\":15897600}");

        GitHubClient.PollResult result = new GitHubClient(transport).pollDeviceToken("c", "DC", 5, 1_000L);

        assertEquals("ghr_1", result.tokens.refreshToken);
        assertEquals(1_000L + 28_800_000L, result.tokens.expiresAtMillis);
    }

    @Test
    public void locationsRoundTripWithSlashesInRefsAndPaths() throws Exception {
        GitHubClient.Location file = new GitHubClient.Location(
            "octo-org", "docs.site", "feature/new docs", "guide/intro #1.md");

        String id = file.serialize();
        GitHubClient.Location parsed = GitHubClient.Location.parse(id);

        assertEquals("octo-org/docs.site/feature%2Fnew%20docs/guide%2Fintro%20%231.md", id);
        assertEquals("octo-org", parsed.owner);
        assertEquals("docs.site", parsed.repo);
        assertEquals("feature/new docs", parsed.ref);
        assertEquals("guide/intro #1.md", parsed.path);
        assertEquals("octo-org/docs.site@feature/new docs/guide/intro #1.md", parsed.describe());
    }

    @Test
    public void locationLevelsParse() throws Exception {
        assertTrue(GitHubClient.Location.parse("").isRoot());
        assertTrue(GitHubClient.Location.parse(null).isRoot());

        GitHubClient.Location repo = GitHubClient.Location.parse("octocat/hello");
        assertTrue(repo.isRepository());
        assertNull(repo.ref);

        GitHubClient.Location branchRoot = GitHubClient.Location.parse("octocat/hello/main");
        assertFalse(branchRoot.isRepository());
        assertEquals("main", branchRoot.ref);
        assertEquals("", branchRoot.path);
        assertEquals("octocat/hello/main", branchRoot.serialize());
    }

    @Test
    public void malformedLocationsAreRejected() throws Exception {
        assertFailsWith("INVALID_CLOUD_FILE_ID", () -> GitHubClient.Location.parse("octocat"));
        assertFailsWith("INVALID_CLOUD_FILE_ID", () -> GitHubClient.Location.parse("../x/main"));
        assertFailsWith("INVALID_CLOUD_FILE_ID", () -> GitHubClient.Location.parse("a/b/main/x/y"));
        assertFailsWith("INVALID_CLOUD_FILE_ID", () -> GitHubClient.Location.parse("a/b//x.md"));
        assertFailsWith("INVALID_CLOUD_FILE_ID", () -> GitHubClient.Location.parse("a/b/main/"));
        assertFailsWith("INVALID_CLOUD_FILE_ID", () -> GitHubClient.Location.parse("a/b/%zz"));
    }

    @Test
    public void repositoryListingFollowsPaginationOnTheApiHostOnly() throws Exception {
        FakeTransport transport = new FakeTransport();
        Map<String, String> firstHeaders = new LinkedHashMap<>();
        firstHeaders.put("Link", "<https://api.github.com/user/repos?page=2>; rel=\"next\", "
            + "<https://api.github.com/user/repos?page=2>; rel=\"last\"");
        transport.enqueue(200, firstHeaders,
            "[{\"full_name\":\"octocat/hello\",\"pushed_at\":\"2026-01-01T00:00:00Z\"}]");
        Map<String, String> secondHeaders = new LinkedHashMap<>();
        secondHeaders.put("Link", "<https://evil.example/user/repos?page=3>; rel=\"next\"");
        transport.enqueue(200, secondHeaders, "[{\"full_name\":\"org/notes\"},{\"full_name\":\"broken\"}]");

        List<OneDriveGraphClient.Entry> entries =
            new GitHubClient(transport).list("TOKEN", GitHubClient.Location.root());

        assertEquals(2, entries.size());
        assertEquals("octocat/hello", entries.get(0).id);
        assertEquals("octocat/hello", entries.get(0).name);
        assertTrue(entries.get(0).isFolder);
        assertEquals("org/notes", entries.get(1).id);
        assertEquals(2, transport.requests.size());
        HttpTransport.Request first = transport.requests.get(0);
        assertTrue(first.url.startsWith("https://api.github.com/user/repos?per_page=100"));
        assertEquals("Bearer TOKEN", first.headers.get("Authorization"));
        assertEquals("application/vnd.github+json", first.headers.get("Accept"));
        assertEquals("2022-11-28", first.headers.get("X-GitHub-Api-Version"));
        assertEquals("https://api.github.com/user/repos?page=2", transport.requests.get(1).url);
    }

    @Test
    public void branchListingPutsTheDefaultBranchFirst() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(200, "{\"default_branch\":\"main\"}");
        transport.enqueue(200, "[{\"name\":\"docs/v2\"},{\"name\":\"main\"}]");

        List<OneDriveGraphClient.Entry> entries = new GitHubClient(transport)
            .list("TOKEN", GitHubClient.Location.parse("octocat/hello"));

        assertEquals("main", entries.get(0).name);
        assertEquals("octocat/hello/main", entries.get(0).id);
        assertEquals("docs/v2", entries.get(1).name);
        assertEquals("octocat/hello/docs%2Fv2", entries.get(1).id);
        assertEquals("https://api.github.com/repos/octocat/hello", transport.requests.get(0).url);
        assertEquals("https://api.github.com/repos/octocat/hello/branches?per_page=100",
            transport.requests.get(1).url);
    }

    @Test
    public void folderListingKeepsFoldersAndMarkdownOnly() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(200, "["
            + "{\"type\":\"dir\",\"name\":\"guide\",\"path\":\"docs/guide\"},"
            + "{\"type\":\"file\",\"name\":\"README.md\",\"path\":\"docs/README.md\",\"size\":12},"
            + "{\"type\":\"file\",\"name\":\"logo.png\",\"path\":\"docs/logo.png\"},"
            + "{\"type\":\"symlink\",\"name\":\"link.md\",\"path\":\"docs/link.md\"},"
            + "{\"type\":\"submodule\",\"name\":\"vendor\",\"path\":\"docs/vendor\"}"
            + "]");

        List<OneDriveGraphClient.Entry> entries = new GitHubClient(transport)
            .list("TOKEN", GitHubClient.Location.parse("octocat/hello/feature%2Fx/docs"));

        assertEquals(2, entries.size());
        assertTrue(entries.get(0).isFolder);
        assertEquals("octocat/hello/feature%2Fx/docs%2Fguide", entries.get(0).id);
        assertEquals("README.md", entries.get(1).name);
        assertEquals(12, entries.get(1).size);
        assertEquals(
            "https://api.github.com/repos/octocat/hello/contents/docs?ref=feature%2Fx",
            transport.requests.get(0).url
        );
    }

    @Test
    public void branchRootListingHasNoPathSegment() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(200, "[]");

        new GitHubClient(transport).list("TOKEN", GitHubClient.Location.parse("octocat/hello/main"));

        assertEquals("https://api.github.com/repos/octocat/hello/contents?ref=main",
            transport.requests.get(0).url);
    }

    @Test
    public void readFetchesMetadataThenRawBytes() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(200, "{\"type\":\"file\",\"name\":\"intro #1.md\","
            + "\"path\":\"guide/intro #1.md\",\"sha\":\"abc123\",\"size\":7}");
        transport.enqueue(200, "# Hello");

        GitHubClient.FileContent file = new GitHubClient(transport).readFile(
            "TOKEN",
            GitHubClient.Location.parse("octocat/hello/main/guide%2Fintro%20%231.md"),
            1024
        );

        assertEquals("intro #1.md", file.name);
        assertEquals("guide/intro #1.md", file.path);
        assertEquals("abc123", file.sha);
        assertEquals("# Hello", new String(file.bytes, StandardCharsets.UTF_8));
        String expectedUrl = "https://api.github.com/repos/octocat/hello/contents/guide/intro%20%231.md?ref=main";
        assertEquals(expectedUrl, transport.requests.get(0).url);
        HttpTransport.Request raw = transport.requests.get(1);
        assertEquals(expectedUrl, raw.url);
        assertEquals("application/vnd.github.raw", raw.headers.get("Accept"));
        assertEquals(1024, raw.maxResponseBytes);
    }

    @Test
    public void readRefusesOversizedFilesBeforeDownloading() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(200, "{\"type\":\"file\",\"name\":\"big.md\",\"path\":\"big.md\",\"sha\":\"s\",\"size\":2048}");

        assertFailsWith("DOCUMENT_TOO_LARGE", () -> new GitHubClient(transport)
            .readFile("TOKEN", GitHubClient.Location.parse("o/r/main/big.md"), 1024));
        assertEquals(1, transport.requests.size());
    }

    @Test
    public void readRejectsFoldersAndBranchRoots() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(200, "[]");
        GitHubClient client = new GitHubClient(transport);

        assertFailsWith("CLOUD_READ_FAILED",
            () -> client.readFile("TOKEN", GitHubClient.Location.parse("o/r/main/docs"), 1024));
        assertFailsWith("INVALID_CLOUD_FILE_ID",
            () -> client.readFile("TOKEN", GitHubClient.Location.parse("o/r/main"), 1024));
    }

    @Test
    public void httpFailuresMapToStableCodes() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(401, "{}");
        transport.enqueue(404, "{}");
        Map<String, String> rateLimited = new LinkedHashMap<>();
        rateLimited.put("x-ratelimit-remaining", "0");
        transport.enqueue(403, rateLimited, "{}");
        transport.enqueue(403, "{}");
        GitHubClient client = new GitHubClient(transport);
        GitHubClient.Location root = GitHubClient.Location.root();

        assertFailsWith("CLOUD_AUTH_EXPIRED", () -> client.list("T", root));
        assertFailsWith("CLOUD_DOCUMENT_NOT_FOUND", () -> client.list("T", root));
        assertFailsWith("CLOUD_UNAVAILABLE", () -> client.list("T", root));
        assertFailsWith("CLOUD_DOCUMENT_NOT_FOUND", () -> client.list("T", root));
    }

    @Test
    public void accountLoginComesFromTheUserEndpoint() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(200, "{\"login\":\"octocat\",\"name\":\"The Octocat\"}");

        assertEquals("octocat", new GitHubClient(transport).getLogin("TOKEN"));
        assertEquals("https://api.github.com/user", transport.requests.get(0).url);
    }
}

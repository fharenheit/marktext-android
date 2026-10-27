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
        transport.enqueue(200, "{\"permissions\":{\"pull\":true,\"push\":false}}");

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
        assertFalse(file.canWrite);
        assertEquals("https://api.github.com/repos/octocat/hello", transport.requests.get(2).url);
    }

    @Test
    public void readTreatsMissingPermissionsAsWritable() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(200, "{\"type\":\"file\",\"name\":\"a.md\",\"path\":\"a.md\",\"sha\":\"s\",\"size\":1}");
        transport.enqueue(200, "x");
        transport.enqueue(200, "{\"full_name\":\"o/r\"}");

        assertTrue(new GitHubClient(transport)
            .readFile("TOKEN", GitHubClient.Location.parse("o/r/main/a.md"), 1024).canWrite);
    }

    @Test
    public void commitPutsBase64ContentWithShaBranchAndMessage() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(200, "{\"content\":{\"name\":\"a b.md\",\"path\":\"docs/a b.md\",\"sha\":\"new-sha\"},"
            + "\"commit\":{\"sha\":\"c0ffee\",\"committer\":{\"date\":\"2026-10-27T01:00:00Z\"}}}");

        GitHubClient.CommitResult result = new GitHubClient(transport).writeFile(
            "TOKEN",
            GitHubClient.Location.parse("octocat/hello/feature%2Fx/docs%2Fa%20b.md"),
            "# Hi \u00e9".getBytes(StandardCharsets.UTF_8),
            "old-sha",
            "Update \"a b.md\""
        );

        assertEquals("a b.md", result.name);
        assertEquals("new-sha", result.sha);
        assertEquals("c0ffee", result.commitSha);
        assertEquals("2026-10-27T01:00:00Z", result.committedAt);
        HttpTransport.Request request = transport.requests.get(0);
        assertEquals("PUT", request.method);
        assertEquals("https://api.github.com/repos/octocat/hello/contents/docs/a%20b.md", request.url);
        assertEquals("Bearer TOKEN", request.headers.get("Authorization"));
        assertEquals("application/json", request.headers.get("Content-Type"));
        org.json.JSONObject body = new org.json.JSONObject(bodyOf(request));
        assertEquals("Update \"a b.md\"", body.getString("message"));
        assertEquals("old-sha", body.getString("sha"));
        assertEquals("feature/x", body.getString("branch"));
        assertEquals(
            java.util.Base64.getEncoder().encodeToString("# Hi \u00e9".getBytes(StandardCharsets.UTF_8)),
            body.getString("content")
        );
    }

    @Test
    public void commitRequiresTheBaselineSha() throws Exception {
        FakeTransport transport = new FakeTransport();

        assertFailsWith("CLOUD_DOCUMENT_CONFLICT", () -> new GitHubClient(transport).writeFile(
            "T", GitHubClient.Location.parse("o/r/main/a.md"), new byte[0], "", "m"));
        assertTrue(transport.requests.isEmpty());
    }

    @Test
    public void commitFailuresMapToStableCodes() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(409, "{\"message\":\"docs/a.md does not match 1234\"}");
        transport.enqueue(409, "{\"message\":\"is at abc but expected def\"}");
        transport.enqueue(409, "{\"message\":\"Repository rule violations found\\n\\nChanges must be made through a pull request.\"}");
        transport.enqueue(422, "{\"message\":\"Invalid request.\"}");
        transport.enqueue(404, "{\"message\":\"Not Found\"}");
        transport.enqueue(403, "{\"message\":\"Resource not accessible by integration\"}");
        transport.enqueue(401, "{}");
        GitHubClient client = new GitHubClient(transport);
        GitHubClient.Location file = GitHubClient.Location.parse("o/r/main/a.md");

        assertFailsWith("CLOUD_DOCUMENT_CONFLICT", () -> client.writeFile("T", file, new byte[0], "s", "m"));
        assertFailsWith("CLOUD_DOCUMENT_CONFLICT", () -> client.writeFile("T", file, new byte[0], "s", "m"));
        try {
            client.writeFile("T", file, new byte[0], "s", "m");
            fail("Expected CLOUD_WRITE_REJECTED");
        } catch (CloudProviderException ex) {
            assertEquals("CLOUD_WRITE_REJECTED", ex.code);
            assertEquals("GitHub rejected the commit: Repository rule violations found", ex.getMessage());
        }
        assertFailsWith("CLOUD_WRITE_REJECTED", () -> client.writeFile("T", file, new byte[0], "s", "m"));
        assertFailsWith("CLOUD_WRITE_FORBIDDEN", () -> client.writeFile("T", file, new byte[0], "s", "m"));
        assertFailsWith("CLOUD_WRITE_FORBIDDEN", () -> client.writeFile("T", file, new byte[0], "s", "m"));
        assertFailsWith("CLOUD_AUTH_EXPIRED", () -> client.writeFile("T", file, new byte[0], "s", "m"));
    }

    @Test
    public void base64MatchesTheJdkEncoderForEveryPaddingLength() {
        java.util.Random random = new java.util.Random(7);
        for (int length = 0; length < 64; length++) {
            byte[] bytes = new byte[length];
            random.nextBytes(bytes);
            assertEquals(java.util.Base64.getEncoder().encodeToString(bytes), GitHubClient.base64(bytes));
        }
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

    @Test
    public void pathsWithDotOrEmptySegmentsAreRejected() throws Exception {
        assertFailsWith("INVALID_CLOUD_FILE_ID", () -> GitHubClient.Location.parse("o/r/main/..%2Fsecret.md"));
        assertFailsWith("INVALID_CLOUD_FILE_ID", () -> GitHubClient.Location.parse("o/r/main/a%2F.%2Fb.md"));
        assertFailsWith("INVALID_CLOUD_FILE_ID", () -> GitHubClient.Location.parse("o/r/main/a%2F%2Fb.md"));
    }

    @Test
    public void blobLinksResolveTheShortestRefNamingAFile() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(404, "{\"message\":\"Not Found\"}");
        transport.enqueue(200, "{\"type\":\"file\",\"name\":\"a.md\",\"path\":\"docs/a.md\",\"sha\":\"s\"}");

        GitHubClient.Location location = new GitHubClient(transport).resolveBlobLink(
            "T", "octocat", "hello", java.util.Arrays.asList("feature", "x", "docs", "a.md"));

        assertEquals("feature/x", location.ref);
        assertEquals("docs/a.md", location.path);
        assertEquals("https://api.github.com/repos/octocat/hello/contents/x/docs/a.md?ref=feature",
            transport.requests.get(0).url);
        assertEquals("https://api.github.com/repos/octocat/hello/contents/docs/a.md?ref=feature%2Fx",
            transport.requests.get(1).url);
    }

    @Test
    public void blobLinksThatNameNothingReadAsNotFound() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(404, "{}");

        assertFailsWith("CLOUD_DOCUMENT_NOT_FOUND", () -> new GitHubClient(transport)
            .resolveBlobLink("T", "o", "r", java.util.Arrays.asList("main", "a.md")));
        assertFailsWith("INVALID_CLOUD_FILE_ID", () -> new GitHubClient(new FakeTransport())
            .resolveBlobLink("T", "o", "r", java.util.Arrays.asList("main", "..", "a.md")));
        assertFailsWith("INVALID_CLOUD_FILE_ID", () -> new GitHubClient(new FakeTransport())
            .resolveBlobLink("T", "..", "r", java.util.Arrays.asList("main", "a.md")));
    }

    @Test
    public void rawReadsCapTheSize() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(200, "0123456789");

        assertFailsWith("DOCUMENT_TOO_LARGE", () -> new GitHubClient(transport)
            .readRaw("T", GitHubClient.Location.parse("o/r/main/a.png"), 4));
        assertEquals(4, transport.requests.get(0).maxResponseBytes);
    }
}

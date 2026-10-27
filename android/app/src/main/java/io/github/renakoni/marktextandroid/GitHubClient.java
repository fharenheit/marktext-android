package io.github.renakoni.marktextandroid;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * GitHub REST client for repository documents, pure JVM over the
 * {@link HttpTransport} seam. Sign-in is the OAuth device flow: GitHub's
 * browser code flow needs a client secret at the token exchange, which an
 * app cannot keep, while the device flow needs only the public client id
 * and no redirect back into the app.
 *
 * Saving is one commit per save through the contents API, checked against
 * the blob sha the document was read at, so a file changed on GitHub in the
 * meantime is never silently overwritten.
 *
 * Browsing is a three-level hierarchy addressed by one opaque id string
 * ({@link Location}): repositories, then branches, then the folders and
 * Markdown files of a branch.
 */
final class GitHubClient {

    static final String WEB = "https://github.com";
    static final String API = "https://api.github.com";
    static final String API_VERSION = "2022-11-28";
    /**
     * Classic OAuth apps need {@code repo} to read private repositories;
     * GitHub Apps ignore the scope and use their configured permissions.
     */
    static final String SCOPES = "repo";
    static final String DEVICE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code";
    /** Follow at most this many Link rel="next" pages per listing. */
    static final int MAX_LIST_PAGES = 10;
    /** Longest branch name (in slash-separated segments) a link split tries. */
    static final int MAX_LINK_REF_SEGMENTS = 5;
    /** GitHub asks clients to add 5 seconds to the interval on slow_down. */
    static final int SLOW_DOWN_STEP_SECONDS = 5;

    private static final Pattern NEXT_LINK = Pattern.compile("<([^>]+)>\\s*;\\s*rel=\"next\"");

    static final class DeviceCode {

        final String deviceCode;
        final String userCode;
        final String verificationUri;
        final int expiresInSeconds;
        final int intervalSeconds;

        DeviceCode(String deviceCode, String userCode, String verificationUri, int expiresInSeconds, int intervalSeconds) {
            this.deviceCode = deviceCode;
            this.userCode = userCode;
            this.verificationUri = verificationUri;
            this.expiresInSeconds = expiresInSeconds;
            this.intervalSeconds = intervalSeconds;
        }
    }

    /** One device-flow poll: still waiting (with the interval to wait next), or tokens. */
    static final class PollResult {

        final CloudTokenStore tokens;
        final int nextIntervalSeconds;

        private PollResult(CloudTokenStore tokens, int nextIntervalSeconds) {
            this.tokens = tokens;
            this.nextIntervalSeconds = nextIntervalSeconds;
        }

        static PollResult pending(int nextIntervalSeconds) {
            return new PollResult(null, nextIntervalSeconds);
        }

        static PollResult authorized(CloudTokenStore tokens) {
            return new PollResult(tokens, 0);
        }

        boolean isPending() {
            return tokens == null;
        }
    }

    static final class FileContent {

        final String name;
        final String path;
        final String sha;
        final byte[] bytes;
        /** Whether the signed-in user may push to the repository. */
        final boolean canWrite;

        FileContent(String name, String path, String sha, byte[] bytes, boolean canWrite) {
            this.name = name;
            this.path = path;
            this.sha = sha;
            this.bytes = bytes;
            this.canWrite = canWrite;
        }
    }

    /** One extra file of a multi-file commit, e.g. an image added on the device. */
    static final class FileChange {

        final String path;
        final byte[] bytes;

        FileChange(String path, byte[] bytes) {
            this.path = path;
            this.bytes = bytes;
        }
    }

    static final class PullRequest {

        final int number;
        final String htmlUrl;

        PullRequest(int number, String htmlUrl) {
            this.number = number;
            this.htmlUrl = htmlUrl;
        }
    }

    static final class CommitResult {

        final String name;
        /** The new blob sha: the version tag for the next commit. */
        final String sha;
        final String commitSha;
        final String committedAt;

        CommitResult(String name, String sha, String commitSha, String committedAt) {
            this.name = name;
            this.sha = sha;
            this.commitSha = commitSha;
            this.committedAt = committedAt;
        }
    }

    /**
     * Where a browser id points: the repository list (empty owner), a
     * repository's branch list (no ref), or a path inside a branch (empty
     * path = branch root).
     *
     * Serialized as {@code owner/repo/<ref>/<path>} with ref and path each
     * percent-encoded as ONE segment, so slashes in branch names and paths
     * can never shift the split. Owner and repository names are restricted
     * by GitHub to characters that need no encoding.
     */
    static final class Location {

        final String owner;
        final String repo;
        final String ref;
        final String path;

        Location(String owner, String repo, String ref, String path) {
            this.owner = owner;
            this.repo = repo;
            this.ref = ref;
            this.path = path;
        }

        boolean isRoot() {
            return owner.length() == 0;
        }

        boolean isRepository() {
            return !isRoot() && ref == null;
        }

        String serialize() {
            if (isRoot()) {
                return "";
            }
            if (ref == null) {
                return owner + "/" + repo;
            }
            String base = owner + "/" + repo + "/" + encodePathSegment(ref);
            return path.length() == 0 ? base : base + "/" + encodePathSegment(path);
        }

        /** Human-readable form for path hints: {@code owner/repo@ref/path}. */
        String describe() {
            if (isRoot()) {
                return "";
            }
            if (ref == null) {
                return owner + "/" + repo;
            }
            return owner + "/" + repo + "@" + ref + (path.length() == 0 ? "" : "/" + path);
        }

        static Location root() {
            return new Location("", "", null, "");
        }

        static Location parse(String id) throws CloudProviderException {
            if (id == null || id.length() == 0) {
                return root();
            }
            String[] parts = id.split("/", -1);
            if (parts.length < 2 || parts.length > 4 || !isValidName(parts[0]) || !isValidName(parts[1])) {
                throw invalidId();
            }
            if (parts.length == 2) {
                return new Location(parts[0], parts[1], null, "");
            }
            String ref = decodePathSegment(parts[2]);
            String path = parts.length == 4 ? decodePathSegment(parts[3]) : "";
            if (ref.length() == 0 || (parts.length == 4 && !isValidPath(path))) {
                throw invalidId();
            }
            return new Location(parts[0], parts[1], ref, path);
        }

        /** Non-empty segments only, and no dot segments: the path lands in API URLs. */
        private static boolean isValidPath(String path) {
            if (path.length() == 0) {
                return false;
            }
            for (String segment : path.split("/", -1)) {
                if (segment.length() == 0 || segment.equals(".") || segment.equals("..")) {
                    return false;
                }
            }
            return true;
        }

        private static boolean isValidName(String value) {
            // The names land in API URL paths unencoded: dot segments must
            // never reach them.
            return value.matches("[A-Za-z0-9._-]+") && !value.equals(".") && !value.equals("..");
        }

        private static CloudProviderException invalidId() {
            return new CloudProviderException("INVALID_CLOUD_FILE_ID", "The GitHub location is invalid");
        }
    }

    private final HttpTransport transport;

    GitHubClient(HttpTransport transport) {
        this.transport = transport;
    }

    DeviceCode requestDeviceCode(String clientId) throws IOException, CloudProviderException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", clientId);
        form.put("scope", SCOPES);
        HttpTransport.Response response = transport.execute(new HttpTransport.Request(
            "POST",
            WEB + "/login/device/code",
            formHeaders(),
            HttpTransport.encodeForm(form).getBytes(StandardCharsets.UTF_8)
        ));

        JSONObject json = parseJson(response.body);
        if (response.status != 200 || json == null || json.has("error")) {
            String error = json == null ? "" : json.optString("error", "");
            if ("device_flow_disabled".equals(error)) {
                throw new CloudProviderException(
                    "CLOUD_CLIENT_ID_MISSING",
                    "The GitHub app in this build does not have the device flow enabled"
                );
            }
            throw new CloudProviderException(
                "CLOUD_AUTH_FAILED",
                "GitHub sign-in could not start"
                    + (error.length() > 0 ? " (" + error + ")" : " (HTTP " + response.status + ")")
            );
        }

        String deviceCode = json.optString("device_code", "");
        String userCode = json.optString("user_code", "");
        String verificationUri = json.optString("verification_uri", "");
        if (deviceCode.length() == 0 || userCode.length() == 0 || !verificationUri.startsWith("https://")) {
            throw new CloudProviderException("CLOUD_AUTH_FAILED", "GitHub sign-in returned an unreadable response");
        }
        return new DeviceCode(
            deviceCode,
            userCode,
            verificationUri,
            json.optInt("expires_in", 900),
            Math.max(1, json.optInt("interval", 5))
        );
    }

    /**
     * One poll of the device-flow token endpoint. GitHub answers HTTP 200
     * with an {@code error} field while the user has not finished, so the
     * error string, not the status, drives the outcome.
     */
    PollResult pollDeviceToken(String clientId, String deviceCode, int intervalSeconds, long nowMillis)
        throws IOException, CloudProviderException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", clientId);
        form.put("device_code", deviceCode);
        form.put("grant_type", DEVICE_GRANT_TYPE);
        HttpTransport.Response response = transport.execute(new HttpTransport.Request(
            "POST",
            WEB + "/login/oauth/access_token",
            formHeaders(),
            HttpTransport.encodeForm(form).getBytes(StandardCharsets.UTF_8)
        ));

        JSONObject json = parseJson(response.body);
        if (json == null) {
            if (response.status == 429 || response.status >= 500) {
                // Transient; keep polling at the current pace.
                return PollResult.pending(intervalSeconds);
            }
            throw new CloudProviderException("CLOUD_AUTH_FAILED", "GitHub sign-in returned an unreadable response");
        }

        String error = json.optString("error", "");
        if ("authorization_pending".equals(error)) {
            return PollResult.pending(intervalSeconds);
        }
        if ("slow_down".equals(error)) {
            int serverInterval = json.optInt("interval", 0);
            return PollResult.pending(Math.max(serverInterval, intervalSeconds + SLOW_DOWN_STEP_SECONDS));
        }
        if ("access_denied".equals(error)) {
            throw new CloudProviderException("CLOUD_AUTH_CANCELED", "The GitHub sign-in was canceled");
        }
        if ("expired_token".equals(error)) {
            throw new CloudProviderException(
                "CLOUD_AUTH_EXPIRED",
                "The GitHub sign-in code expired; start the sign-in again"
            );
        }
        if (error.length() > 0) {
            throw new CloudProviderException("CLOUD_AUTH_FAILED", "GitHub sign-in failed (" + error + ")");
        }
        if (response.status != 200) {
            throw new CloudProviderException(
                "CLOUD_AUTH_FAILED",
                "GitHub sign-in failed (HTTP " + response.status + ")"
            );
        }

        String accessToken = json.optString("access_token", "");
        if (accessToken.length() == 0) {
            throw new CloudProviderException("CLOUD_AUTH_FAILED", "GitHub sign-in returned no access token");
        }
        // OAuth apps (and GitHub Apps with token expiration turned off)
        // issue tokens that never expire and carry no refresh token.
        String refreshToken = json.optString("refresh_token", "");
        long expiresIn = json.optLong("expires_in", 0);
        long expiresAt = refreshToken.length() > 0 && expiresIn > 0
            ? nowMillis + expiresIn * 1000
            : CloudTokenStore.NEVER_EXPIRES;
        if (expiresAt == CloudTokenStore.NEVER_EXPIRES) {
            refreshToken = "";
        }
        return PollResult.authorized(new CloudTokenStore(accessToken, refreshToken, expiresAt, null));
    }

    String getLogin(String accessToken) throws IOException, CloudProviderException {
        return apiJson(accessToken, API + "/user").optString("login", "");
    }

    /**
     * Lists one level of the browser hierarchy. Repository and branch
     * levels return only folders; a branch level returns folders plus
     * Markdown candidates.
     */
    List<OneDriveGraphClient.Entry> list(String accessToken, Location location)
        throws IOException, CloudProviderException {
        if (location.isRoot()) {
            return listRepositories(accessToken);
        }
        if (location.isRepository()) {
            return listBranches(accessToken, location);
        }
        return listFolder(accessToken, location);
    }

    private List<OneDriveGraphClient.Entry> listRepositories(String accessToken)
        throws IOException, CloudProviderException {
        List<OneDriveGraphClient.Entry> entries = new ArrayList<>();
        String url = API + "/user/repos?per_page=100&sort=updated"
            + "&affiliation=owner,collaborator,organization_member";
        for (int page = 0; page < MAX_LIST_PAGES && url != null; page++) {
            HttpTransport.Response response = apiGet(accessToken, url);
            JSONArray values = parseJsonArray(response.body);
            if (values == null) {
                throw new CloudProviderException("CLOUD_REQUEST_FAILED", "GitHub returned an unreadable response");
            }
            for (int index = 0; index < values.length(); index++) {
                JSONObject repo = values.optJSONObject(index);
                if (repo == null) {
                    continue;
                }
                String fullName = repo.optString("full_name", "");
                int slash = fullName.indexOf('/');
                if (slash <= 0) {
                    continue;
                }
                Location location = new Location(
                    fullName.substring(0, slash),
                    fullName.substring(slash + 1),
                    null,
                    ""
                );
                entries.add(new OneDriveGraphClient.Entry(
                    location.serialize(),
                    fullName,
                    true,
                    0,
                    repo.optString("pushed_at", "")
                ));
            }
            url = nextPageUrl(response);
        }
        return entries;
    }

    private List<OneDriveGraphClient.Entry> listBranches(String accessToken, Location location)
        throws IOException, CloudProviderException {
        String repoUrl = API + "/repos/" + location.owner + "/" + location.repo;
        String defaultBranch = apiJson(accessToken, repoUrl).optString("default_branch", "");

        List<OneDriveGraphClient.Entry> entries = new ArrayList<>();
        String url = repoUrl + "/branches?per_page=100";
        for (int page = 0; page < MAX_LIST_PAGES && url != null; page++) {
            HttpTransport.Response response = apiGet(accessToken, url);
            JSONArray values = parseJsonArray(response.body);
            if (values == null) {
                throw new CloudProviderException("CLOUD_REQUEST_FAILED", "GitHub returned an unreadable response");
            }
            for (int index = 0; index < values.length(); index++) {
                JSONObject branch = values.optJSONObject(index);
                String name = branch == null ? "" : branch.optString("name", "");
                if (name.length() == 0) {
                    continue;
                }
                OneDriveGraphClient.Entry entry = new OneDriveGraphClient.Entry(
                    new Location(location.owner, location.repo, name, "").serialize(),
                    name,
                    true,
                    0,
                    ""
                );
                // The default branch leads; the rest keep GitHub's order.
                if (name.equals(defaultBranch)) {
                    entries.add(0, entry);
                } else {
                    entries.add(entry);
                }
            }
            url = nextPageUrl(response);
        }
        return entries;
    }

    private List<OneDriveGraphClient.Entry> listFolder(String accessToken, Location location)
        throws IOException, CloudProviderException {
        HttpTransport.Response response = apiGet(accessToken, contentsUrl(location));
        JSONArray values = parseJsonArray(response.body);
        if (values == null) {
            // A file path answers with an object, not an array.
            throw new CloudProviderException("CLOUD_REQUEST_FAILED", "This GitHub location is not a folder");
        }

        List<OneDriveGraphClient.Entry> entries = new ArrayList<>();
        for (int index = 0; index < values.length(); index++) {
            JSONObject item = values.optJSONObject(index);
            if (item == null) {
                continue;
            }
            String type = item.optString("type", "");
            String name = item.optString("name", "");
            String path = item.optString("path", "");
            boolean isFolder = "dir".equals(type);
            // Symlinks and submodules cannot be read through this API.
            if (path.length() == 0 || (!isFolder && !"file".equals(type))) {
                continue;
            }
            if (!isFolder && !OneDriveGraphClient.isListableDocumentName(name)) {
                continue;
            }
            entries.add(new OneDriveGraphClient.Entry(
                new Location(location.owner, location.repo, location.ref, path).serialize(),
                name,
                isFolder,
                item.optLong("size", 0),
                ""
            ));
        }
        return entries;
    }

    FileContent readFile(String accessToken, Location location, int maxBytes)
        throws IOException, CloudProviderException {
        if (location.ref == null || location.path.length() == 0) {
            throw new CloudProviderException("INVALID_CLOUD_FILE_ID", "The GitHub location is not a file");
        }

        // Metadata first: the JSON form carries the blob sha (the version
        // tag for later conflict-checked commits) and the size, so an
        // oversized file is refused before its body is fetched.
        HttpTransport.Response metadataResponse = apiGet(accessToken, contentsUrl(location));
        JSONObject metadata = parseJson(metadataResponse.body);
        if (metadata == null || !"file".equals(metadata.optString("type", ""))) {
            throw new CloudProviderException("CLOUD_READ_FAILED", "This GitHub location is not a file");
        }
        if (metadata.optLong("size", 0) > maxBytes) {
            throw new CloudProviderException(
                "DOCUMENT_TOO_LARGE",
                "Markdown document is larger than the current 5 MB limit"
            );
        }

        byte[] bytes = readRaw(accessToken, location, maxBytes);
        return new FileContent(
            metadata.optString("name", ""),
            metadata.optString("path", location.path),
            metadata.optString("sha", ""),
            bytes,
            canPush(accessToken, location)
        );
    }

    /**
     * The file's bytes through the raw media type: no base64, and no 1 MB
     * cap of the inline JSON content. Also serves repository images.
     */
    byte[] readRaw(String accessToken, Location location, int maxBytes)
        throws IOException, CloudProviderException {
        if (location.ref == null || location.path.length() == 0) {
            throw new CloudProviderException("INVALID_CLOUD_FILE_ID", "The GitHub location is not a file");
        }
        Map<String, String> rawHeaders = apiHeaders(accessToken);
        rawHeaders.put("Accept", "application/vnd.github.raw");
        HttpTransport.Response download = transport.execute(new HttpTransport.Request(
            "GET",
            contentsUrl(location),
            rawHeaders,
            null,
            maxBytes
        ));
        if (download.status != 200) {
            throw statusException(download, "CLOUD_READ_FAILED", "GitHub download failed");
        }
        byte[] bytes = download.body == null ? new byte[0] : download.body;
        if (bytes.length > maxBytes) {
            throw new CloudProviderException(
                "DOCUMENT_TOO_LARGE",
                "The GitHub file is larger than the " + (maxBytes / (1024 * 1024)) + " MB limit"
            );
        }
        return bytes;
    }

    /**
     * Splits a blob link's {@code <ref>/<path>} tail. Branch names may
     * contain slashes, so the URL alone is ambiguous: the shortest ref whose
     * remainder names a file on GitHub wins, which matches how GitHub itself
     * resolves these links for all but pathological branch layouts.
     *
     * @param segments the decoded path segments after {@code /blob/}
     */
    Location resolveBlobLink(String accessToken, String owner, String repo, List<String> segments)
        throws IOException, CloudProviderException {
        Location repoLocation = Location.parse(owner + "/" + repo);
        int maxRefSegments = Math.min(segments.size() - 1, MAX_LINK_REF_SEGMENTS);
        for (int refSegments = 1; refSegments <= maxRefSegments; refSegments++) {
            String ref = joinSegments(segments.subList(0, refSegments));
            String path = joinSegments(segments.subList(refSegments, segments.size()));
            if (ref.length() == 0 || path.length() == 0) {
                continue;
            }
            // Round-trip through parse: the segments come from a shared link
            // and must pass the same validation as browser ids.
            Location candidate = Location.parse(
                new Location(repoLocation.owner, repoLocation.repo, ref, path).serialize());
            HttpTransport.Response response = transport.execute(new HttpTransport.Request(
                "GET",
                contentsUrl(candidate),
                apiHeaders(accessToken),
                null
            ));
            if (response.status == 404) {
                continue;
            }
            if (response.status != 200) {
                throw statusException(response, "CLOUD_REQUEST_FAILED", "GitHub request failed");
            }
            JSONObject metadata = parseJson(response.body);
            if (metadata == null || !"file".equals(metadata.optString("type", ""))) {
                throw new CloudProviderException("CLOUD_READ_FAILED", "This GitHub link does not point to a file");
            }
            return candidate;
        }
        throw new CloudProviderException(
            "CLOUD_DOCUMENT_NOT_FOUND",
            "This GitHub document was moved, deleted, or is not accessible"
        );
    }

    /** String.join is API 26; minSdk is 24. */
    private static String joinSegments(List<String> segments) {
        StringBuilder joined = new StringBuilder();
        for (String segment : segments) {
            if (joined.length() > 0) {
                joined.append('/');
            }
            joined.append(segment);
        }
        return joined.toString();
    }

    /**
     * Whether the user may push. A repository without a permissions block
     * (some token kinds omit it) reads as writable: the commit itself is
     * the authority and fails with a clear error if it is not.
     */
    private boolean canPush(String accessToken, Location location) throws IOException, CloudProviderException {
        JSONObject repo = apiJson(accessToken, API + "/repos/" + location.owner + "/" + location.repo);
        JSONObject permissions = repo.optJSONObject("permissions");
        return permissions == null || permissions.optBoolean("push", false);
    }

    /**
     * Commits new content for an existing file on its branch. The expected
     * blob sha is mandatory: GitHub refuses the commit when the file moved
     * on, which surfaces as CLOUD_DOCUMENT_CONFLICT.
     */
    CommitResult writeFile(
        String accessToken,
        Location location,
        byte[] bytes,
        String expectedSha,
        String message
    ) throws IOException, CloudProviderException {
        if (location.ref == null || location.path.length() == 0) {
            throw new CloudProviderException("INVALID_CLOUD_FILE_ID", "The GitHub location is not a file");
        }
        if (expectedSha == null || expectedSha.length() == 0) {
            throw new CloudProviderException(
                "CLOUD_DOCUMENT_CONFLICT",
                "This document must be reopened before it can be committed"
            );
        }

        byte[] body;
        try {
            JSONObject json = new JSONObject();
            json.put("message", message);
            json.put("content", base64(bytes));
            json.put("sha", expectedSha);
            json.put("branch", location.ref);
            body = json.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException ex) {
            throw new IllegalStateException("Commit request serialization failed", ex);
        }

        Map<String, String> headers = apiHeaders(accessToken);
        headers.put("Content-Type", "application/json");
        HttpTransport.Response response = transport.execute(new HttpTransport.Request(
            "PUT",
            contentsUrl(location, false),
            headers,
            body
        ));

        JSONObject json = parseJson(response.body);
        if (response.status != 200 && response.status != 201) {
            throw commitException(response, json);
        }
        JSONObject content = json == null ? null : json.optJSONObject("content");
        if (content == null || content.optString("sha", "").length() == 0) {
            throw new CloudProviderException("CLOUD_WRITE_FAILED", "GitHub commit returned an unreadable response");
        }
        JSONObject commit = json.optJSONObject("commit");
        JSONObject committer = commit == null ? null : commit.optJSONObject("committer");
        return new CommitResult(
            content.optString("name", ""),
            content.optString("sha", ""),
            commit == null ? "" : commit.optString("sha", ""),
            committer == null ? "" : committer.optString("date", "")
        );
    }

    /**
     * Commits the document plus extra files (images added on the device) as
     * ONE commit through the Git Data API: blobs, a tree on top of the
     * branch head, a commit, and a non-forced ref update. The document's
     * blob at the head must still be the one it was read at, and the ref
     * update refuses to move if the branch advanced meanwhile — either way
     * the result is CLOUD_DOCUMENT_CONFLICT rather than lost work.
     */
    CommitResult commitFiles(
        String accessToken,
        Location document,
        byte[] documentBytes,
        String expectedSha,
        List<FileChange> extraFiles,
        String message
    ) throws IOException, CloudProviderException {
        if (document.ref == null || document.path.length() == 0) {
            throw new CloudProviderException("INVALID_CLOUD_FILE_ID", "The GitHub location is not a file");
        }
        if (expectedSha == null || expectedSha.length() == 0) {
            throw new CloudProviderException(
                "CLOUD_DOCUMENT_CONFLICT",
                "This document must be reopened before it can be committed"
            );
        }
        for (FileChange change : extraFiles) {
            // Same validation as browser ids: no dot or empty segments.
            Location.parse(new Location(document.owner, document.repo, document.ref, change.path).serialize());
        }

        String headSha = getBranchHead(accessToken, document.owner, document.repo, document.ref);
        Location atHead = new Location(document.owner, document.repo, headSha, document.path);
        JSONObject current = apiJson(accessToken, contentsUrl(atHead));
        if (!expectedSha.equals(current.optString("sha", ""))) {
            throw new CloudProviderException(
                "CLOUD_DOCUMENT_CONFLICT",
                "This document changed on GitHub since it was opened"
            );
        }
        String repoUrl = API + "/repos/" + document.owner + "/" + document.repo;
        JSONObject headCommit = apiJson(accessToken, repoUrl + "/git/commits/" + headSha);
        JSONObject headTree = headCommit.optJSONObject("tree");
        String baseTreeSha = headTree == null ? "" : headTree.optString("sha", "");
        if (baseTreeSha.length() == 0) {
            throw new CloudProviderException("CLOUD_WRITE_FAILED", "GitHub returned an unreadable commit");
        }

        try {
            JSONArray tree = new JSONArray();
            String documentBlobSha = createBlob(accessToken, repoUrl, documentBytes);
            tree.put(treeEntry(document.path, documentBlobSha));
            for (FileChange change : extraFiles) {
                tree.put(treeEntry(change.path, createBlob(accessToken, repoUrl, change.bytes)));
            }

            JSONObject treeRequest = new JSONObject();
            treeRequest.put("base_tree", baseTreeSha);
            treeRequest.put("tree", tree);
            String treeSha = requiredSha(apiSend(accessToken, "POST", repoUrl + "/git/trees", treeRequest, 201));

            JSONObject commitRequest = new JSONObject();
            commitRequest.put("message", message);
            commitRequest.put("tree", treeSha);
            commitRequest.put("parents", new JSONArray().put(headSha));
            JSONObject commit = apiSend(accessToken, "POST", repoUrl + "/git/commits", commitRequest, 201);
            String commitSha = requiredSha(commit);

            JSONObject refUpdate = new JSONObject();
            refUpdate.put("sha", commitSha);
            refUpdate.put("force", false);
            HttpTransport.Response moved = send(accessToken, "PATCH", refUrl(repoUrl, document.ref), refUpdate);
            if (moved.status == 422 || moved.status == 409) {
                // Not a fast-forward: someone pushed to the branch meanwhile.
                throw new CloudProviderException(
                    "CLOUD_DOCUMENT_CONFLICT",
                    "This branch changed on GitHub while committing; reopen the document and try again"
                );
            }
            if (moved.status != 200) {
                throw commitException(moved, parseJson(moved.body));
            }

            JSONObject committer = commit.optJSONObject("committer");
            String path = document.path;
            return new CommitResult(
                path.substring(path.lastIndexOf('/') + 1),
                documentBlobSha,
                commitSha,
                committer == null ? "" : committer.optString("date", "")
            );
        } catch (JSONException ex) {
            throw new IllegalStateException("Commit request serialization failed", ex);
        }
    }

    /** Commit sha at the tip of a branch. */
    String getBranchHead(String accessToken, String owner, String repo, String branch)
        throws IOException, CloudProviderException {
        String repoUrl = API + "/repos/" + Location.parse(owner + "/" + repo).serialize();
        JSONObject ref = apiJson(accessToken, refUrl(repoUrl, branch).replace("/git/refs/", "/git/ref/"));
        JSONObject object = ref.optJSONObject("object");
        String sha = object == null ? "" : object.optString("sha", "");
        if (sha.length() == 0) {
            throw new CloudProviderException("CLOUD_REQUEST_FAILED", "GitHub returned an unreadable branch");
        }
        return sha;
    }

    void createBranch(String accessToken, String owner, String repo, String branch, String fromSha)
        throws IOException, CloudProviderException {
        if (!isValidBranchName(branch)) {
            throw new CloudProviderException("INVALID_BRANCH_NAME", "This branch name is not valid");
        }
        String repoUrl = API + "/repos/" + Location.parse(owner + "/" + repo).serialize();
        try {
            JSONObject request = new JSONObject();
            request.put("ref", "refs/heads/" + branch);
            request.put("sha", fromSha);
            HttpTransport.Response response = send(accessToken, "POST", repoUrl + "/git/refs", request);
            if (response.status == 422) {
                throw new CloudProviderException(
                    "CLOUD_NAME_EXISTS",
                    "A branch with this name already exists on GitHub"
                );
            }
            if (response.status != 201) {
                throw commitException(response, parseJson(response.body));
            }
        } catch (JSONException ex) {
            throw new IllegalStateException("Branch request serialization failed", ex);
        }
    }

    /** Best effort: a branch created for a commit that then failed is removed again. */
    void deleteBranch(String accessToken, String owner, String repo, String branch) {
        try {
            String repoUrl = API + "/repos/" + Location.parse(owner + "/" + repo).serialize();
            transport.execute(new HttpTransport.Request("DELETE", refUrl(repoUrl, branch), apiHeaders(accessToken), null));
        } catch (IOException | CloudProviderException ignored) {
            // The empty branch only clutters the repository; nothing is lost.
        }
    }

    PullRequest openPullRequest(
        String accessToken,
        String owner,
        String repo,
        String head,
        String base,
        String title,
        String body
    ) throws IOException, CloudProviderException {
        String repoUrl = API + "/repos/" + Location.parse(owner + "/" + repo).serialize();
        try {
            JSONObject request = new JSONObject();
            request.put("title", title);
            request.put("head", head);
            request.put("base", base);
            request.put("body", body);
            HttpTransport.Response response = send(accessToken, "POST", repoUrl + "/pulls", request);
            JSONObject json = parseJson(response.body);
            if (response.status != 201) {
                if (response.status == 422) {
                    String detail = json == null ? "" : firstLine(json.optString("message", ""));
                    JSONArray errors = json == null ? null : json.optJSONArray("errors");
                    JSONObject first = errors == null ? null : errors.optJSONObject(0);
                    if (first != null && first.optString("message", "").length() > 0) {
                        detail = firstLine(first.optString("message", ""));
                    }
                    throw new CloudProviderException(
                        "CLOUD_WRITE_REJECTED",
                        "GitHub did not open the pull request" + (detail.length() > 0 ? ": " + detail : "")
                    );
                }
                throw commitException(response, json);
            }
            String htmlUrl = json == null ? "" : json.optString("html_url", "");
            if (!htmlUrl.startsWith("https://")) {
                throw new CloudProviderException("CLOUD_WRITE_FAILED", "GitHub returned an unreadable pull request");
            }
            return new PullRequest(json.optInt("number", 0), htmlUrl);
        } catch (JSONException ex) {
            throw new IllegalStateException("Pull request serialization failed", ex);
        }
    }

    /**
     * A conservative subset of git's ref-name rules: ASCII letters, digits,
     * '.', '_', '-' and '/' separators; no empty, dot-leading or '.lock'
     * segments, no '..', and nothing GitHub would reinterpret.
     */
    static boolean isValidBranchName(String name) {
        if (name == null || name.length() == 0 || name.length() > 100 || !name.matches("[A-Za-z0-9._/-]+")) {
            return false;
        }
        if (name.contains("..") || name.startsWith("-") || name.endsWith(".")) {
            return false;
        }
        for (String segment : name.split("/", -1)) {
            if (segment.length() == 0 || segment.startsWith(".") || segment.endsWith(".lock")) {
                return false;
            }
        }
        return true;
    }

    private String createBlob(String accessToken, String repoUrl, byte[] bytes)
        throws IOException, CloudProviderException, JSONException {
        JSONObject request = new JSONObject();
        request.put("content", base64(bytes));
        request.put("encoding", "base64");
        return requiredSha(apiSend(accessToken, "POST", repoUrl + "/git/blobs", request, 201));
    }

    private static JSONObject treeEntry(String path, String blobSha) throws JSONException {
        JSONObject entry = new JSONObject();
        entry.put("path", path);
        entry.put("mode", "100644");
        entry.put("type", "blob");
        entry.put("sha", blobSha);
        return entry;
    }

    private static String requiredSha(JSONObject json) throws CloudProviderException {
        String sha = json.optString("sha", "");
        if (sha.length() == 0) {
            throw new CloudProviderException("CLOUD_WRITE_FAILED", "GitHub commit returned an unreadable response");
        }
        return sha;
    }

    /** {@code .../git/refs/heads/<branch>} with each branch segment encoded. */
    private static String refUrl(String repoUrl, String branch) {
        StringBuilder url = new StringBuilder(repoUrl).append("/git/refs/heads");
        for (String segment : branch.split("/")) {
            url.append('/').append(encodePathSegment(segment));
        }
        return url.toString();
    }

    private JSONObject apiSend(String accessToken, String method, String url, JSONObject body, int expectedStatus)
        throws IOException, CloudProviderException {
        HttpTransport.Response response = send(accessToken, method, url, body);
        JSONObject json = parseJson(response.body);
        if (response.status != expectedStatus) {
            throw commitException(response, json);
        }
        if (json == null) {
            throw new CloudProviderException("CLOUD_WRITE_FAILED", "GitHub returned an unreadable response");
        }
        return json;
    }

    private HttpTransport.Response send(String accessToken, String method, String url, JSONObject body)
        throws IOException {
        Map<String, String> headers = apiHeaders(accessToken);
        headers.put("Content-Type", "application/json");
        return transport.execute(new HttpTransport.Request(
            method,
            url,
            headers,
            body.toString().getBytes(StandardCharsets.UTF_8)
        ));
    }

    private static CloudProviderException commitException(HttpTransport.Response response, JSONObject json) {
        String detail = json == null ? "" : firstLine(json.optString("message", ""));
        if (response.status == 409 && (detail.contains("does not match") || detail.contains("but expected"))) {
            return new CloudProviderException(
                "CLOUD_DOCUMENT_CONFLICT",
                "This document changed on GitHub since it was opened"
            );
        }
        if (response.status == 409 || response.status == 422) {
            // Protected branches, rulesets and similar refusals.
            return new CloudProviderException(
                "CLOUD_WRITE_REJECTED",
                "GitHub rejected the commit" + (detail.length() > 0 ? ": " + detail : "")
            );
        }
        String remaining = response.header("X-RateLimit-Remaining");
        if ((response.status == 403 || response.status == 404) && !"0".equals(remaining)) {
            return new CloudProviderException(
                "CLOUD_WRITE_FORBIDDEN",
                "This GitHub account cannot commit to this repository"
            );
        }
        return statusException(response, "CLOUD_WRITE_FAILED", "GitHub commit failed");
    }

    /** Standard Base64 with padding; java.util.Base64 needs API 26 and minSdk is 24. */
    static String base64(byte[] bytes) {
        final char[] alphabet =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
        StringBuilder out = new StringBuilder((bytes.length + 2) / 3 * 4);
        int index = 0;
        while (index + 2 < bytes.length) {
            int chunk = (bytes[index] & 0xff) << 16 | (bytes[index + 1] & 0xff) << 8 | (bytes[index + 2] & 0xff);
            out.append(alphabet[chunk >>> 18])
                .append(alphabet[(chunk >>> 12) & 0x3f])
                .append(alphabet[(chunk >>> 6) & 0x3f])
                .append(alphabet[chunk & 0x3f]);
            index += 3;
        }
        int remaining = bytes.length - index;
        if (remaining == 1) {
            int chunk = (bytes[index] & 0xff) << 16;
            out.append(alphabet[chunk >>> 18]).append(alphabet[(chunk >>> 12) & 0x3f]).append("==");
        } else if (remaining == 2) {
            int chunk = (bytes[index] & 0xff) << 16 | (bytes[index + 1] & 0xff) << 8;
            out.append(alphabet[chunk >>> 18])
                .append(alphabet[(chunk >>> 12) & 0x3f])
                .append(alphabet[(chunk >>> 6) & 0x3f])
                .append('=');
        }
        return out.toString();
    }

    private static String firstLine(String value) {
        int lineEnd = value.indexOf('\n');
        return (lineEnd >= 0 ? value.substring(0, lineEnd) : value).trim();
    }

    private static String contentsUrl(Location location) {
        return contentsUrl(location, true);
    }

    /** The PUT form names the branch in its body; reads pin it with ?ref=. */
    private static String contentsUrl(Location location, boolean withRef) {
        StringBuilder url = new StringBuilder(API)
            .append("/repos/").append(location.owner).append('/').append(location.repo)
            .append("/contents");
        for (String segment : location.path.split("/")) {
            if (segment.length() > 0) {
                url.append('/').append(encodePathSegment(segment));
            }
        }
        return withRef
            ? url.append("?ref=").append(encodePathSegment(location.ref)).toString()
            : url.toString();
    }

    static String nextPageUrl(HttpTransport.Response response) {
        String link = response.header("Link");
        if (link == null) {
            return null;
        }
        Matcher matcher = NEXT_LINK.matcher(link);
        if (!matcher.find()) {
            return null;
        }
        String next = matcher.group(1);
        // Only ever follow GitHub's own API host with the bearer token.
        return next.startsWith(API + "/") ? next : null;
    }

    private JSONObject apiJson(String accessToken, String url) throws IOException, CloudProviderException {
        JSONObject json = parseJson(apiGet(accessToken, url).body);
        if (json == null) {
            throw new CloudProviderException("CLOUD_REQUEST_FAILED", "GitHub returned an unreadable response");
        }
        return json;
    }

    private HttpTransport.Response apiGet(String accessToken, String url) throws IOException, CloudProviderException {
        HttpTransport.Response response = transport.execute(new HttpTransport.Request(
            "GET",
            url,
            apiHeaders(accessToken),
            null
        ));
        if (response.status != 200) {
            throw statusException(response, "CLOUD_REQUEST_FAILED", "GitHub request failed");
        }
        return response;
    }

    private static CloudProviderException statusException(
        HttpTransport.Response response,
        String fallbackCode,
        String fallbackMessage
    ) {
        if (response.status == 401) {
            return new CloudProviderException(
                "CLOUD_AUTH_EXPIRED",
                "GitHub access is no longer authorized; connect the account again"
            );
        }
        // GitHub answers 403 for rate limits as well as missing access.
        String remaining = response.header("X-RateLimit-Remaining");
        if ((response.status == 403 && "0".equals(remaining)) || response.status == 429 || response.status >= 500) {
            return new CloudProviderException(
                "CLOUD_UNAVAILABLE",
                "GitHub is temporarily unavailable; try again shortly"
            );
        }
        if (response.status == 404 || response.status == 403) {
            // Private repositories the token cannot see read as 404.
            return new CloudProviderException(
                "CLOUD_DOCUMENT_NOT_FOUND",
                "This GitHub document was moved, deleted, or is not accessible"
            );
        }
        return new CloudProviderException(fallbackCode, fallbackMessage + " (HTTP " + response.status + ")");
    }

    private static Map<String, String> apiHeaders(String accessToken) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + accessToken);
        headers.put("Accept", "application/vnd.github+json");
        headers.put("X-GitHub-Api-Version", API_VERSION);
        headers.put("User-Agent", "MarkText-Android");
        return headers;
    }

    private static Map<String, String> formHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        headers.put("Accept", "application/json");
        headers.put("User-Agent", "MarkText-Android");
        return headers;
    }

    private static JSONObject parseJson(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            return new JSONObject(new String(body, StandardCharsets.UTF_8));
        } catch (JSONException ex) {
            return null;
        }
    }

    private static JSONArray parseJsonArray(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            return new JSONArray(new String(body, StandardCharsets.UTF_8));
        } catch (JSONException ex) {
            return null;
        }
    }

    /** Percent-encodes one URL path segment (URLEncoder's '+' is form encoding, not path). */
    static String encodePathSegment(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException ex) {
            throw new IllegalStateException("UTF-8 unavailable", ex);
        }
    }

    private static String decodePathSegment(String value) throws CloudProviderException {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (UnsupportedEncodingException ex) {
            throw new IllegalStateException("UTF-8 unavailable", ex);
        } catch (IllegalArgumentException ex) {
            throw new CloudProviderException("INVALID_CLOUD_FILE_ID", "The GitHub location is invalid");
        }
    }
}

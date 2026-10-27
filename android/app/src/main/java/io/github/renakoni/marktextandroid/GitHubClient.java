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

        FileContent(String name, String path, String sha, byte[] bytes) {
            this.name = name;
            this.path = path;
            this.sha = sha;
            this.bytes = bytes;
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
            if (ref.length() == 0 || (parts.length == 4 && path.length() == 0)) {
                throw invalidId();
            }
            return new Location(parts[0], parts[1], ref, path);
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

        // The raw media type returns the bytes directly (no base64, and no
        // 1 MB cap of the inline JSON content).
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
                "Markdown document is larger than the current 5 MB limit"
            );
        }
        return new FileContent(
            metadata.optString("name", ""),
            metadata.optString("path", location.path),
            metadata.optString("sha", ""),
            bytes
        );
    }

    private static String contentsUrl(Location location) {
        StringBuilder url = new StringBuilder(API)
            .append("/repos/").append(location.owner).append('/').append(location.repo)
            .append("/contents");
        for (String segment : location.path.split("/")) {
            if (segment.length() > 0) {
                url.append('/').append(encodePathSegment(segment));
            }
        }
        return url.append("?ref=").append(encodePathSegment(location.ref)).toString();
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

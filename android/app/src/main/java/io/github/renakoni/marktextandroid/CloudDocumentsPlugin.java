package io.github.renakoni.marktextandroid;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Cloud documents over provider web APIs — no provider app required
 * (#185 Phase 2: OneDrive, then Google Drive, then GitHub). OAuth runs in
 * the system browser (embedded WebViews are provider-blocked); the redirect
 * lands back here through a per-provider intent filter. GitHub instead signs
 * in with the device flow (a code the user enters on github.com, polled
 * here), since its browser flow needs a client secret. All network work
 * runs on a single-thread executor; protocol logic lives in the pure-JVM
 * {@link OneDriveGraphClient}, {@link GoogleDriveClient} and
 * {@link GitHubClient}.
 */
@CapacitorPlugin(name = "CloudDocuments")
public class CloudDocumentsPlugin extends Plugin {

    private static final String TAG = "MarkTextAndroid";
    private static final String PROVIDER_ONEDRIVE = "onedrive";
    private static final String PROVIDER_GOOGLEDRIVE = "googledrive";
    private static final String PROVIDER_GITHUB = "github";
    private static final String PROVIDER_WEBDAV = "webdav";
    static final String CLOUD_PREFS = "cloud_documents";
    private static final String PREF_ONEDRIVE_TOKENS = "onedrive_tokens";
    private static final String PREF_ONEDRIVE_PENDING_AUTH = "onedrive_pending_auth";
    private static final String PREF_GOOGLEDRIVE_TOKENS = "googledrive_tokens";
    private static final String PREF_GOOGLEDRIVE_PENDING_AUTH = "googledrive_pending_auth";
    static final String PREF_GITHUB_TOKENS = "github_tokens";
    /** Unused: the device flow keeps no pending record across restarts. */
    private static final String PREF_GITHUB_PENDING_AUTH = "github_pending_auth";
    private static final String EVENT_AUTH_COMPLETED = "cloudAuthCompleted";
    private static final String EVENT_AUTH_PENDING = "cloudAuthPending";
    private static final String OAUTH_HOST = "oauth";
    private static final String OAUTH_ONEDRIVE_PATH = "/onedrive";
    private static final String OAUTH_GOOGLEDRIVE_PATH = "/oauth2redirect";

    private final ExecutorService cloudExecutor = Executors.newSingleThreadExecutor();
    private final CharsetSniffer cloudCharsetSniffer = new IcuCharsetSniffer();
    private final HttpTransport cloudTransport = new UrlConnectionTransport();
    private final OneDriveGraphClient graphClient = new OneDriveGraphClient(cloudTransport);
    private final GoogleDriveClient driveClient = new GoogleDriveClient(cloudTransport);
    private final GitHubClient githubClient = new GitHubClient(cloudTransport);
    /**
     * Device-flow polls wait out GitHub's interval here, off the shared
     * cloud executor, so a pending sign-in never blocks document I/O.
     */
    private final ScheduledExecutorService githubPollExecutor = Executors.newSingleThreadScheduledExecutor();
    /** Guards {@link #githubDeviceGeneration} against the token save. */
    private final Object githubDeviceLock = new Object();
    /**
     * Bumped by every device-flow start, cancel, disconnect and completion;
     * a poll whose generation is stale stops silently, so a canceled or
     * superseded sign-in can never save tokens.
     */
    private int githubDeviceGeneration = 0;
    private final GitHubImageServer githubImageServer = new GitHubImageServer(
        githubClient,
        // Qualified: a bare reference to the later-declared field is illegal here.
        () -> requireFreshTokens(this.githubAuth).accessToken
    );

    /**
     * Per-provider wiring for the shared OAuth machinery: the pending/token
     * storage keys, the redirect shape, and the protocol client calls. The
     * connect/complete/refresh/disconnect flows themselves are identical
     * across providers and stay in one copy below.
     */
    private abstract class ProviderAuth {

        final String id;
        final String label;
        final String pendingPrefKey;
        final String tokensPrefKey;
        /**
         * True from the moment a valid redirect is consumed until its token
         * exchange emitted a result. connect/disconnect reject while set, so
         * a user cannot interleave a second sign-in (or a sign-out) with an
         * exchange that will still save tokens when it lands.
         */
        volatile boolean exchangeInFlight = false;

        ProviderAuth(String id, String label, String pendingPrefKey, String tokensPrefKey) {
            this.id = id;
            this.label = label;
            this.pendingPrefKey = pendingPrefKey;
            this.tokensPrefKey = tokensPrefKey;
        }

        abstract String clientId();

        abstract String redirectUri();

        abstract boolean matchesRedirect(Uri data);

        /** pickMode: "document" (default) or "folder" (save-as destination). */
        abstract String authorizeUrl(String state, String codeChallenge, String pickMode);

        abstract CloudTokenStore exchangeCode(String code, String verifier, long nowMillis)
            throws IOException, CloudProviderException;

        abstract CloudTokenStore refresh(CloudTokenStore tokens, long nowMillis)
            throws IOException, CloudProviderException;

        /** Best-effort account label; the connection works without it. */
        abstract String fetchAccountName(String accessToken) throws IOException, CloudProviderException;

        /** Best-effort hook after stored tokens were dropped on disconnect. */
        void onDisconnect(CloudTokenStore tokens) {}
    }

    private final ProviderAuth oneDriveAuth = new ProviderAuth(
        PROVIDER_ONEDRIVE, "OneDrive", PREF_ONEDRIVE_PENDING_AUTH, PREF_ONEDRIVE_TOKENS) {

        @Override
        String clientId() {
            return CloudAuthConfig.ONEDRIVE_CLIENT_ID;
        }

        @Override
        String redirectUri() {
            // The package name IS the applicationId (".debug"-suffixed on
            // debug builds), matching the ${applicationId} manifest scheme.
            return getContext().getPackageName() + "://" + OAUTH_HOST + OAUTH_ONEDRIVE_PATH;
        }

        @Override
        boolean matchesRedirect(Uri data) {
            return getContext().getPackageName().equals(data.getScheme())
                && OAUTH_HOST.equals(data.getHost())
                && OAUTH_ONEDRIVE_PATH.equals(data.getPath());
        }

        @Override
        String authorizeUrl(String state, String codeChallenge, String pickMode) {
            // OneDrive folders are picked in the in-app browser; the OAuth
            // trip is a plain sign-in regardless of mode.
            return OneDriveGraphClient.buildAuthorizeUrl(clientId(), redirectUri(), state, codeChallenge);
        }

        @Override
        CloudTokenStore exchangeCode(String code, String verifier, long nowMillis)
            throws IOException, CloudProviderException {
            return graphClient.exchangeCode(clientId(), redirectUri(), code, verifier, nowMillis).tokens;
        }

        @Override
        CloudTokenStore refresh(CloudTokenStore tokens, long nowMillis)
            throws IOException, CloudProviderException {
            return graphClient.refresh(clientId(), tokens, nowMillis).tokens;
        }

        @Override
        String fetchAccountName(String accessToken) throws IOException, CloudProviderException {
            OneDriveGraphClient.Account account = graphClient.getAccount(accessToken);
            return account.userPrincipalName.length() > 0
                ? account.userPrincipalName
                : account.displayName;
        }
    };

    private final ProviderAuth googleDriveAuth = new ProviderAuth(
        PROVIDER_GOOGLEDRIVE, "Google Drive", PREF_GOOGLEDRIVE_PENDING_AUTH, PREF_GOOGLEDRIVE_TOKENS) {

        @Override
        String clientId() {
            // Per-variant resource: Google binds Android clients to the
            // package name + signing certificate, so the debug and release
            // builds cannot share one id and each carries its own. An empty
            // id reads as provider-unavailable.
            return getContext().getString(R.string.googledrive_client_id).trim();
        }

        @Override
        String redirectUri() {
            // Google's documented native-app form: the reversed client id
            // as the scheme, no authority.
            return GoogleDriveClient.redirectSchemeFor(clientId()) + ":" + OAUTH_GOOGLEDRIVE_PATH;
        }

        @Override
        boolean matchesRedirect(Uri data) {
            // Only auth redirects ever use the reversed-client-id scheme.
            String scheme = GoogleDriveClient.redirectSchemeFor(clientId());
            return scheme.length() > 0 && scheme.equals(data.getScheme());
        }

        @Override
        String authorizeUrl(String state, String codeChallenge, String pickMode) {
            // The authorization page hosts the picker itself
            // (trigger_onepick); the redirect returns picked_file_ids.
            // "folder" restricts it to folders for save-as destinations.
            return "folder".equals(pickMode)
                ? GoogleDriveClient.buildFolderPickerAuthorizeUrl(clientId(), redirectUri(), state, codeChallenge)
                : GoogleDriveClient.buildPickerAuthorizeUrl(clientId(), redirectUri(), state, codeChallenge);
        }

        @Override
        CloudTokenStore exchangeCode(String code, String verifier, long nowMillis)
            throws IOException, CloudProviderException {
            return driveClient.exchangeCode(clientId(), redirectUri(), code, verifier, nowMillis).tokens;
        }

        @Override
        CloudTokenStore refresh(CloudTokenStore tokens, long nowMillis)
            throws IOException, CloudProviderException {
            return driveClient.refresh(clientId(), tokens, nowMillis).tokens;
        }

        @Override
        String fetchAccountName(String accessToken) throws IOException, CloudProviderException {
            return driveClient.getAccountName(accessToken);
        }

        @Override
        void onDisconnect(CloudTokenStore tokens) {
            // Revoking the refresh token retires the whole grant server-side;
            // the local state is already cleared, so failures only log.
            cloudExecutor.execute(() -> {
                try {
                    if (driveClient.revoke(tokens.refreshToken)) {
                        Log.i(TAG, "Revoked the Google Drive grant");
                    } else {
                        Log.w(TAG, "Google Drive did not confirm the token revocation");
                    }
                } catch (IOException ex) {
                    Log.w(TAG, "Google Drive token revocation failed", ex);
                }
            });
        }
    };

    /**
     * GitHub shares the token storage, account state, refresh gate and
     * disconnect of the OAuth providers, but signs in through the device
     * flow ({@link #startGitHubDeviceFlow}): the redirect members below are
     * never reached.
     */
    private final ProviderAuth githubAuth = new ProviderAuth(
        PROVIDER_GITHUB, "GitHub", PREF_GITHUB_PENDING_AUTH, PREF_GITHUB_TOKENS) {

        @Override
        String clientId() {
            // Build-time resource (app/build.gradle): GitHub binds no
            // signing certificate, so one id serves every variant.
            return getContext().getString(R.string.github_client_id).trim();
        }

        @Override
        String redirectUri() {
            return "";
        }

        @Override
        boolean matchesRedirect(Uri data) {
            return false;
        }

        @Override
        String authorizeUrl(String state, String codeChallenge, String pickMode) {
            throw new UnsupportedOperationException("GitHub signs in with the device flow");
        }

        @Override
        CloudTokenStore exchangeCode(String code, String verifier, long nowMillis)
            throws CloudProviderException {
            throw new CloudProviderException("CLOUD_AUTH_FAILED", "GitHub signs in with the device flow");
        }

        @Override
        CloudTokenStore refresh(CloudTokenStore tokens, long nowMillis) throws CloudProviderException {
            // Only expiring GitHub App tokens get here, and refreshing them
            // needs the client secret the app cannot hold: sign in again.
            throw new CloudProviderException(
                "CLOUD_AUTH_EXPIRED",
                "The GitHub sign-in has expired; connect the account again"
            );
        }

        @Override
        String fetchAccountName(String accessToken) throws IOException, CloudProviderException {
            return githubClient.getLogin(accessToken);
        }
    };

    @Override
    public void load() {
        super.load();
        // Plugins load before the bridge loads its first page, so the
        // image endpoint is in place for every request.
        if (getBridge() != null) {
            getBridge().setWebViewClient(new GitHubImageWebViewClient(getBridge(), githubImageServer));
        }
        Activity activity = getActivity();
        if (activity != null) {
            // A cold-start redirect arrives as the launch intent, never
            // through handleOnNewIntent.
            maybeCompleteAuth(activity.getIntent());
        }
    }

    @Override
    protected void handleOnNewIntent(Intent intent) {
        super.handleOnNewIntent(intent);
        maybeCompleteAuth(intent);
    }

    @PluginMethod
    public void getCloudAccountState(PluginCall call) {
        String provider = call.getString("provider", "");
        if (PROVIDER_WEBDAV.equals(provider)) {
            JSObject result = new JSObject();
            result.put("connected", false);
            result.put("available", false);
            result.put("accountName", JSObject.NULL);
            call.resolve(result);
            return;
        }
        ProviderAuth auth = providerAuthFor(provider);
        if (auth == null) {
            call.reject("Unknown cloud provider", "CLOUD_PROVIDER_UNKNOWN");
            return;
        }

        CloudTokenStore tokens = readTokens(auth);
        JSObject result = new JSObject();
        result.put("connected", tokens != null);
        result.put("available", auth.clientId().length() > 0);
        result.put(
            "accountName",
            tokens == null || tokens.accountName == null ? JSObject.NULL : tokens.accountName
        );
        call.resolve(result);
    }

    @PluginMethod
    public void connectCloudAccount(PluginCall call) {
        String provider = call.getString("provider", "");
        if (PROVIDER_WEBDAV.equals(provider)) {
            call.reject("WebDAV support is not available yet", WebDavCloudClient.NOT_IMPLEMENTED_CODE);
            return;
        }
        ProviderAuth auth = providerAuthFor(provider);
        if (auth == null) {
            call.reject("Unknown cloud provider", "CLOUD_PROVIDER_UNKNOWN");
            return;
        }
        if (auth.clientId().length() == 0) {
            call.reject(
                "The " + auth.label + " client id is not configured in this build",
                "CLOUD_CLIENT_ID_MISSING"
            );
            return;
        }
        if (auth == githubAuth) {
            startGitHubDeviceFlow(call);
            return;
        }
        if (auth.exchangeInFlight) {
            call.reject(
                "The " + auth.label + " sign-in is still completing",
                "CLOUD_AUTH_IN_PROGRESS"
            );
            return;
        }
        Activity activity = getActivity();
        if (activity == null) {
            call.reject("No Android activity is available", "CLOUD_AUTH_FAILED");
            return;
        }

        String verifier = PkceUtil.generateVerifier();
        String state = PkceUtil.generateVerifier();
        try {
            JSONObject pending = new JSONObject();
            pending.put("state", state);
            pending.put("verifier", verifier);
            cloudPrefs().edit().putString(auth.pendingPrefKey, pending.toString()).apply();
        } catch (JSONException ex) {
            call.reject("Could not start the " + auth.label + " sign-in", "CLOUD_AUTH_FAILED", ex);
            return;
        }

        String authorizeUrl = auth.authorizeUrl(
            state,
            PkceUtil.challengeFor(verifier),
            call.getString("pickMode", "document")
        );
        try {
            // A Custom Tab keeps the browser trip visually inside the app
            // (slides over it, returns on redirect) while still being the
            // real system browser with the user's Google/Microsoft session
            // — embedded WebViews are provider-blocked AND session-less.
            // Browsers without Custom Tab support open it as a plain link.
            androidx.browser.customtabs.CustomTabsIntent customTab =
                new androidx.browser.customtabs.CustomTabsIntent.Builder()
                    .setShowTitle(true)
                    .build();
            customTab.launchUrl(activity, Uri.parse(authorizeUrl));
        } catch (ActivityNotFoundException ex) {
            cloudPrefs().edit().remove(auth.pendingPrefKey).apply();
            call.reject(
                "No browser is available for the " + auth.label + " sign-in",
                "CLOUD_BROWSER_UNAVAILABLE",
                ex
            );
            return;
        }

        Log.i(TAG, "Started " + auth.label + " sign-in in the system browser");
        JSObject result = new JSObject();
        result.put("started", true);
        call.resolve(result);
    }

    /**
     * Abandons an in-progress device-flow sign-in (GitHub). Browser-based
     * sign-ins have nothing to cancel here: backing out of the browser
     * simply never redirects.
     */
    @PluginMethod
    public void cancelCloudAuth(PluginCall call) {
        if (PROVIDER_GITHUB.equals(call.getString("provider", ""))) {
            cancelGitHubDeviceFlow();
        }
        call.resolve(new JSObject());
    }

    /**
     * Scopes the GitHub image endpoint to the open document's repository
     * and branch; an empty or non-GitHub id turns it off.
     */
    @PluginMethod
    public void setCloudImageContext(PluginCall call) {
        String provider = call.getString("provider", "");
        String fileId = call.getString("fileId", "");
        GitHubClient.Location location = null;
        if (PROVIDER_GITHUB.equals(provider) && fileId.length() > 0) {
            try {
                location = GitHubClient.Location.parse(fileId);
            } catch (CloudProviderException ex) {
                call.reject(ex.getMessage(), ex.code, ex);
                return;
            }
        }
        githubImageServer.setContext(location);
        call.resolve(new JSObject());
    }

    /**
     * Turns a github.com blob link into a document id. The ref/path split
     * of such links is ambiguous (branch names may contain slashes), so it
     * is resolved against GitHub.
     */
    @PluginMethod
    public void resolveGitHubLink(PluginCall call) {
        String owner = call.getString("owner", "");
        String repo = call.getString("repo", "");
        JSArray segmentValues = call.getArray("segments", new JSArray());
        List<String> segments = new java.util.ArrayList<>();
        for (int index = 0; index < segmentValues.length(); index++) {
            String segment = segmentValues.optString(index, "");
            if (segment.length() > 0) {
                segments.add(segment);
            }
        }
        if (segments.size() < 2 || !OneDriveGraphClient.isListableDocumentName(segments.get(segments.size() - 1))) {
            call.reject("This GitHub link does not point to a Markdown document", "UNSUPPORTED_DOCUMENT");
            return;
        }

        cloudExecutor.execute(() -> {
            try {
                CloudTokenStore tokens = requireFreshTokens(githubAuth);
                GitHubClient.Location location =
                    githubClient.resolveBlobLink(tokens.accessToken, owner, repo, segments);
                JSObject result = new JSObject();
                result.put("fileId", location.serialize());
                call.resolve(result);
            } catch (CloudProviderException ex) {
                Log.w(TAG, "GitHub link resolution failed: " + ex.getMessage());
                call.reject(ex.getMessage(), ex.code, ex);
            } catch (IOException ex) {
                Log.w(TAG, "GitHub link resolution failed", ex);
                call.reject("Could not reach GitHub", "CLOUD_NETWORK_FAILED", ex);
            }
        });
    }

    @PluginMethod
    public void disconnectCloudAccount(PluginCall call) {
        String provider = call.getString("provider", "");
        ProviderAuth auth = providerAuthFor(provider);
        if (auth == null) {
            call.reject("Unknown cloud provider", "CLOUD_PROVIDER_UNKNOWN");
            return;
        }
        if (auth.exchangeInFlight) {
            // A disconnect racing the exchange would be undone the moment
            // the exchange saves its tokens; keep the lifecycle linear.
            call.reject(
                "The " + auth.label + " sign-in is still completing",
                "CLOUD_AUTH_IN_PROGRESS"
            );
            return;
        }
        if (auth == githubAuth) {
            // A sign-in still polling would otherwise reconnect right after.
            cancelGitHubDeviceFlow();
            githubImageServer.clear();
        }
        CloudTokenStore tokens = readTokens(auth);
        cloudPrefs()
            .edit()
            .remove(auth.tokensPrefKey)
            .remove(auth.pendingPrefKey)
            .apply();
        Log.i(TAG, "Disconnected the " + auth.label + " account");
        call.resolve(new JSObject());
        if (tokens != null) {
            auth.onDisconnect(tokens);
        }
    }

    @PluginMethod
    public void listCloudFolder(PluginCall call) {
        String provider = call.getString("provider", "");
        String folderId = call.getString("folderId", "");
        ProviderAuth auth = requireDocumentProvider(call, provider);
        if (auth == null) {
            return;
        }
        if (auth == googleDriveAuth) {
            // Google Drive files are chosen through the Google Picker;
            // drive.file cannot enumerate a folder the app was never shown.
            call.reject(
                auth.label + " does not support in-app folder browsing",
                "CLOUD_OPERATION_UNSUPPORTED"
            );
            return;
        }

        cloudExecutor.execute(() -> {
            try {
                CloudTokenStore tokens = requireFreshTokens(auth);
                List<OneDriveGraphClient.Entry> entries;
                if (auth == githubAuth) {
                    GitHubClient.Location location = GitHubClient.Location.parse(folderId);
                    entries = githubClient.list(tokens.accessToken, location);
                    // Repositories keep GitHub's recently-pushed order and
                    // branches lead with the default one; only folder
                    // contents read better sorted.
                    if (!location.isRoot() && !location.isRepository()) {
                        sortFoldersFirst(entries);
                    }
                } else {
                    entries = graphClient.listFolder(tokens.accessToken, folderId);
                    sortFoldersFirst(entries);
                }

                JSArray values = new JSArray();
                for (OneDriveGraphClient.Entry entry : entries) {
                    JSObject value = new JSObject();
                    value.put("id", entry.id);
                    value.put("name", entry.name);
                    value.put("isFolder", entry.isFolder);
                    value.put("size", entry.size);
                    value.put("lastModified", entry.lastModified);
                    values.put(value);
                }
                JSObject result = new JSObject();
                result.put("entries", values);
                call.resolve(result);
            } catch (CloudProviderException ex) {
                Log.w(TAG, auth.label + " folder listing failed: " + ex.getMessage());
                call.reject(ex.getMessage(), ex.code, ex);
            } catch (IOException ex) {
                Log.w(TAG, auth.label + " folder listing failed", ex);
                call.reject("Could not reach " + auth.label, "CLOUD_NETWORK_FAILED", ex);
            }
        });
    }

    @PluginMethod
    public void readCloudDocument(PluginCall call) {
        String provider = call.getString("provider", "");
        String fileId = call.getString("fileId", "");
        String defaultEncoding = MarkdownCodec.normalizeEncoding(call.getString("defaultEncoding", "utf8"));
        boolean autoDetectEncoding = call.getBoolean("autoDetectEncoding", true);
        ProviderAuth auth = requireDocumentProvider(call, provider);
        if (auth == null) {
            return;
        }
        if (fileId.length() == 0) {
            call.reject("A cloud file id is required", "INVALID_CLOUD_FILE_ID");
            return;
        }

        cloudExecutor.execute(() -> {
            try {
                CloudTokenStore tokens = requireFreshTokens(auth);
                String name;
                String versionTag;
                boolean canWrite;
                byte[] bytes;
                String pathHint = null;
                if (auth == githubAuth) {
                    GitHubClient.Location location = GitHubClient.Location.parse(fileId);
                    GitHubClient.FileContent file = githubClient.readFile(
                        tokens.accessToken,
                        location,
                        MarkdownCodec.MAX_MARKDOWN_BYTES
                    );
                    name = file.name;
                    versionTag = file.sha;
                    canWrite = file.canWrite;
                    bytes = file.bytes;
                    pathHint = location.describe();
                    // Scope the image endpoint before the editor renders;
                    // the web layer's own context call can arrive after the
                    // first image requests.
                    githubImageServer.setContext(location);
                } else if (auth == googleDriveAuth) {
                    GoogleDriveClient.FileContent file = driveClient.readFile(
                        tokens.accessToken,
                        fileId,
                        MarkdownCodec.MAX_MARKDOWN_BYTES
                    );
                    name = file.name;
                    versionTag = file.headRevisionId;
                    canWrite = file.canWrite;
                    bytes = file.bytes;
                } else {
                    OneDriveGraphClient.FileContent file = graphClient.readFile(
                        tokens.accessToken,
                        fileId,
                        MarkdownCodec.MAX_MARKDOWN_BYTES
                    );
                    name = file.name;
                    versionTag = file.eTag;
                    canWrite = true;
                    bytes = file.bytes;
                }
                DecodedMarkdown decoded = MarkdownCodec.decode(
                    bytes,
                    defaultEncoding,
                    autoDetectEncoding,
                    cloudCharsetSniffer
                );

                JSObject result = new JSObject();
                result.put("fileId", fileId);
                result.put("displayName", name);
                result.put("markdown", decoded.markdown);
                result.put("encoding", MarkdownCodec.normalizeEncoding(decoded.encoding));
                result.put("hasEncodingBom", decoded.hasBom);
                result.put("eTag", versionTag);
                result.put("providerName", auth.label);
                result.put("canWrite", canWrite);
                result.put("pathHint", pathHint == null ? name : pathHint);
                Log.i(TAG, "Read " + auth.label + " document: " + safeForLog(name));
                call.resolve(result);
            } catch (CloudProviderException ex) {
                Log.w(TAG, auth.label + " document read failed: " + ex.getMessage());
                call.reject(ex.getMessage(), ex.code, ex);
            } catch (DocumentReadException ex) {
                Log.w(TAG, auth.label + " document read rejected: " + ex.getMessage());
                call.reject(ex.getMessage(), ex.code, ex);
            } catch (IOException ex) {
                Log.w(TAG, auth.label + " document read failed", ex);
                call.reject("Could not reach " + auth.label, "CLOUD_NETWORK_FAILED", ex);
            }
        });
    }

    /** Save-as: creates a new document in the chosen provider folder. */
    @PluginMethod
    public void createCloudDocument(PluginCall call) {
        String provider = call.getString("provider", "");
        String parentId = call.getString("parentId", "");
        String name = call.getString("name", "");
        String markdown = call.getString("markdown", null);
        String encoding = MarkdownCodec.normalizeEncoding(call.getString("encoding", "utf8"));
        boolean writeBom = call.getBoolean("writeBom", false);
        ProviderAuth auth = requireDocumentProvider(call, provider);
        if (auth == null || rejectCreateUnsupported(call, auth)) {
            return;
        }
        if (name.trim().length() == 0) {
            call.reject("A document name is required", "INVALID_CLOUD_FILE_NAME");
            return;
        }
        if (markdown == null) {
            call.reject("Markdown content is required", "INVALID_MARKDOWN");
            return;
        }

        String documentName = name.trim();
        cloudExecutor.execute(() -> {
            try {
                byte[] bytes = MarkdownCodec.validateBytes(
                    markdown,
                    new MarkdownWriteOptions(encoding, writeBom)
                );
                CloudTokenStore tokens = requireFreshTokens(auth);
                String fileId;
                String displayName;
                String versionTag;
                String lastModified;
                if (auth == googleDriveAuth) {
                    GoogleDriveClient.CreateResult created =
                        driveClient.createFile(tokens.accessToken, parentId, documentName, bytes);
                    fileId = created.id;
                    displayName = created.name;
                    versionTag = created.headRevisionId;
                    lastModified = created.lastModified;
                } else {
                    OneDriveGraphClient.CreateResult created =
                        graphClient.createFile(tokens.accessToken, parentId, documentName, bytes);
                    fileId = created.id;
                    displayName = created.name;
                    versionTag = created.eTag;
                    lastModified = created.lastModified;
                }

                JSObject result = new JSObject();
                result.put("fileId", fileId);
                result.put("displayName", displayName);
                result.put("eTag", versionTag);
                result.put("lastModified", lastModified);
                Log.i(TAG, "Created " + auth.label + " document: " + safeForLog(displayName));
                call.resolve(result);
            } catch (CloudProviderException ex) {
                Log.w(TAG, auth.label + " document create failed: " + ex.getMessage());
                call.reject(ex.getMessage(), ex.code, ex);
            } catch (DocumentReadException ex) {
                Log.w(TAG, auth.label + " document create rejected: " + ex.getMessage());
                call.reject(ex.getMessage(), ex.code, ex);
            } catch (IOException ex) {
                Log.w(TAG, auth.label + " document create failed", ex);
                call.reject("Could not reach " + auth.label, "CLOUD_NETWORK_FAILED", ex);
            }
        });
    }

    @PluginMethod
    public void writeCloudDocument(PluginCall call) {
        String provider = call.getString("provider", "");
        String fileId = call.getString("fileId", "");
        String markdown = call.getString("markdown", null);
        String eTag = call.getString("eTag", "");
        String encoding = MarkdownCodec.normalizeEncoding(call.getString("encoding", "utf8"));
        boolean writeBom = call.getBoolean("writeBom", false);
        String commitMessage = call.getString("commitMessage", "").trim();
        String newBranch = call.getString("newBranch", "").trim();
        boolean openPullRequest = call.getBoolean("openPullRequest", false);
        JSArray localImages = call.getArray("localImages", new JSArray());
        ProviderAuth auth = requireDocumentProvider(call, provider);
        if (auth == null) {
            return;
        }
        if (auth != githubAuth && (newBranch.length() > 0 || localImages.length() > 0)) {
            call.reject("Branches and image uploads are GitHub-only", "CLOUD_OPERATION_UNSUPPORTED");
            return;
        }
        if (newBranch.length() > 0 && !GitHubClient.isValidBranchName(newBranch)) {
            call.reject("This branch name is not valid", "INVALID_BRANCH_NAME");
            return;
        }
        if (fileId.length() == 0) {
            call.reject("A cloud file id is required", "INVALID_CLOUD_FILE_ID");
            return;
        }
        if (markdown == null) {
            call.reject("Markdown content is required", "INVALID_MARKDOWN");
            return;
        }

        cloudExecutor.execute(() -> {
            try {
                byte[] bytes = MarkdownCodec.validateBytes(
                    markdown,
                    new MarkdownWriteOptions(encoding, writeBom)
                );
                CloudTokenStore tokens = requireFreshTokens(auth);
                String name;
                String versionTag;
                String lastModified;
                String writtenFileId = fileId;
                GitHubClient.PullRequest pullRequest = null;
                String pullRequestError = null;
                if (auth == githubAuth) {
                    GitHubClient.Location location = GitHubClient.Location.parse(fileId);
                    String message = commitMessage.length() > 0 ? commitMessage : "Update " + location.path;
                    List<GitHubClient.FileChange> images = readLocalImagesForCommit(localImages);
                    GitHubClient.Location target = location;
                    if (newBranch.length() > 0) {
                        String headSha = githubClient.getBranchHead(
                            tokens.accessToken, location.owner, location.repo, location.ref);
                        githubClient.createBranch(
                            tokens.accessToken, location.owner, location.repo, newBranch, headSha);
                        target = new GitHubClient.Location(location.owner, location.repo, newBranch, location.path);
                    }
                    GitHubClient.CommitResult committed;
                    try {
                        committed = images.isEmpty()
                            ? githubClient.writeFile(tokens.accessToken, target, bytes, eTag, message)
                            : githubClient.commitFiles(tokens.accessToken, target, bytes, eTag, images, message);
                    } catch (CloudProviderException | IOException ex) {
                        if (target != location) {
                            githubClient.deleteBranch(
                                tokens.accessToken, location.owner, location.repo, newBranch);
                        }
                        throw ex;
                    }
                    if (target != location && openPullRequest) {
                        // The commit already landed on the new branch; a
                        // failed pull request must not report it as lost.
                        try {
                            pullRequest = githubClient.openPullRequest(
                                tokens.accessToken,
                                location.owner,
                                location.repo,
                                newBranch,
                                location.ref,
                                firstLine(message),
                                "Edited with MarkText for Android."
                            );
                        } catch (CloudProviderException | IOException ex) {
                            Log.w(TAG, "GitHub pull request failed: " + ex.getMessage());
                            pullRequestError = ex.getMessage();
                        }
                    }
                    name = committed.name;
                    versionTag = committed.sha;
                    lastModified = committed.committedAt;
                    writtenFileId = target.serialize();
                } else if (auth == googleDriveAuth) {
                    GoogleDriveClient.WriteResult written =
                        driveClient.writeFile(tokens.accessToken, fileId, bytes, eTag);
                    name = written.name;
                    versionTag = written.headRevisionId;
                    lastModified = written.lastModified;
                } else {
                    OneDriveGraphClient.WriteResult written =
                        graphClient.writeFile(tokens.accessToken, fileId, bytes, eTag);
                    name = written.name;
                    versionTag = written.eTag;
                    lastModified = written.lastModified;
                }

                JSObject result = new JSObject();
                result.put("fileId", writtenFileId);
                result.put("displayName", name);
                result.put("eTag", versionTag);
                result.put("lastModified", lastModified);
                if (pullRequest != null) {
                    result.put("pullRequestNumber", pullRequest.number);
                    result.put("pullRequestUrl", pullRequest.htmlUrl);
                }
                if (pullRequestError != null) {
                    result.put("pullRequestError", pullRequestError);
                }
                Log.i(TAG, "Wrote " + auth.label + " document: " + safeForLog(name));
                call.resolve(result);
            } catch (CloudProviderException ex) {
                Log.w(TAG, auth.label + " document write failed: " + ex.getMessage());
                call.reject(ex.getMessage(), ex.code, ex);
            } catch (DocumentReadException ex) {
                Log.w(TAG, auth.label + " document write rejected: " + ex.getMessage());
                call.reject(ex.getMessage(), ex.code, ex);
            } catch (IOException ex) {
                Log.w(TAG, auth.label + " document write failed", ex);
                call.reject("Could not reach " + auth.label, "CLOUD_NETWORK_FAILED", ex);
            }
        });
    }

    private ProviderAuth providerAuthFor(String provider) {
        if (PROVIDER_ONEDRIVE.equals(provider)) {
            return oneDriveAuth;
        }
        if (PROVIDER_GOOGLEDRIVE.equals(provider)) {
            return googleDriveAuth;
        }
        if (PROVIDER_GITHUB.equals(provider)) {
            return githubAuth;
        }
        return null;
    }

    /**
     * Images inserted on the device (app-local files) that the commit adds
     * next to the document. Names come from the web layer, so only plain
     * file names inside the imported-image directory are accepted.
     */
    private List<GitHubClient.FileChange> readLocalImagesForCommit(JSArray values)
        throws CloudProviderException, IOException {
        List<GitHubClient.FileChange> changes = new java.util.ArrayList<>();
        java.io.File directory = new java.io.File(getContext().getFilesDir(), "images");
        long totalBytes = 0;
        for (int index = 0; index < values.length(); index++) {
            JSONObject value = values.optJSONObject(index);
            String name = value == null ? "" : value.optString("name", "");
            String path = value == null ? "" : value.optString("path", "");
            if (!name.matches("[A-Za-z0-9_-][A-Za-z0-9._-]*") || path.length() == 0) {
                throw new CloudProviderException("INVALID_IMAGE_RESULT", "An image to commit has an invalid name");
            }
            java.io.File file = new java.io.File(directory, name);
            if (!file.isFile()) {
                throw new CloudProviderException("IMAGE_NOT_FOUND", "An image in this document is missing on this device");
            }
            if (file.length() > GitHubImageServer.MAX_IMAGE_BYTES) {
                throw new CloudProviderException("IMAGE_TOO_LARGE", "An image in this document is larger than 15 MB");
            }
            totalBytes += file.length();
            if (totalBytes > 4L * GitHubImageServer.MAX_IMAGE_BYTES) {
                throw new CloudProviderException("IMAGE_TOO_LARGE", "The images in this commit exceed 60 MB together");
            }
            byte[] bytes = new byte[(int) file.length()];
            try (java.io.DataInputStream input = new java.io.DataInputStream(new java.io.FileInputStream(file))) {
                input.readFully(bytes);
            }
            changes.add(new GitHubClient.FileChange(path, bytes));
        }
        return changes;
    }

    private static String firstLine(String value) {
        int lineEnd = value.indexOf('\n');
        return (lineEnd >= 0 ? value.substring(0, lineEnd) : value).trim();
    }

    /** Save-as cannot target GitHub yet: new files need a folder pick there. */
    private boolean rejectCreateUnsupported(PluginCall call, ProviderAuth auth) {
        if (auth != githubAuth) {
            return false;
        }
        call.reject("Creating documents on GitHub is not available yet", "CLOUD_OPERATION_UNSUPPORTED");
        return true;
    }

    /** Folders first, then case-insensitive names. */
    private static void sortFoldersFirst(List<OneDriveGraphClient.Entry> entries) {
        entries.sort(
            Comparator.comparing((OneDriveGraphClient.Entry entry) -> !entry.isFolder)
                .thenComparing(entry -> entry.name, String.CASE_INSENSITIVE_ORDER)
        );
    }

    private void startGitHubDeviceFlow(PluginCall call) {
        String clientId = githubAuth.clientId();
        int generation;
        synchronized (githubDeviceLock) {
            // A second start supersedes the first: its polls go stale.
            generation = ++githubDeviceGeneration;
        }
        cloudExecutor.execute(() -> {
            try {
                GitHubClient.DeviceCode code = githubClient.requestDeviceCode(clientId);
                synchronized (githubDeviceLock) {
                    if (generation != githubDeviceGeneration) {
                        call.reject("The GitHub sign-in was canceled", "CLOUD_AUTH_CANCELED");
                        return;
                    }
                }
                long deadline = System.currentTimeMillis() + code.expiresInSeconds * 1000L;
                scheduleGitHubPoll(generation, clientId, code.deviceCode, code.intervalSeconds, deadline);

                Log.i(TAG, "Started GitHub device sign-in");
                JSObject result = new JSObject();
                result.put("started", true);
                result.put("userCode", code.userCode);
                result.put("verificationUri", code.verificationUri);
                result.put("expiresIn", code.expiresInSeconds);
                call.resolve(result);
            } catch (CloudProviderException ex) {
                Log.w(TAG, "GitHub sign-in could not start: " + ex.getMessage());
                call.reject(ex.getMessage(), ex.code, ex);
            } catch (IOException ex) {
                Log.w(TAG, "GitHub sign-in could not start", ex);
                call.reject("Could not reach GitHub", "CLOUD_NETWORK_FAILED", ex);
            }
        });
    }

    private void scheduleGitHubPoll(
        int generation,
        String clientId,
        String deviceCode,
        int intervalSeconds,
        long deadlineMillis
    ) {
        githubPollExecutor.schedule(
            () -> pollGitHubDeviceToken(generation, clientId, deviceCode, intervalSeconds, deadlineMillis),
            intervalSeconds,
            TimeUnit.SECONDS
        );
    }

    private void pollGitHubDeviceToken(
        int generation,
        String clientId,
        String deviceCode,
        int intervalSeconds,
        long deadlineMillis
    ) {
        if (!isGitHubDeviceFlowCurrent(generation)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now >= deadlineMillis) {
            finishGitHubDeviceFlow(generation, null, "CLOUD_AUTH_EXPIRED",
                "The GitHub sign-in code expired; start the sign-in again");
            return;
        }
        try {
            GitHubClient.PollResult poll =
                githubClient.pollDeviceToken(clientId, deviceCode, intervalSeconds, now);
            if (poll.isPending()) {
                scheduleGitHubPoll(generation, clientId, deviceCode, poll.nextIntervalSeconds, deadlineMillis);
                return;
            }
            String login = null;
            try {
                login = githubClient.getLogin(poll.tokens.accessToken);
            } catch (CloudProviderException | IOException ex) {
                // The account label is cosmetic; the connection still works.
                Log.w(TAG, "Could not read the GitHub account name", ex);
            }
            finishGitHubDeviceFlow(generation, poll.tokens.withAccountName(login), null, null);
        } catch (CloudProviderException ex) {
            Log.w(TAG, "GitHub sign-in did not complete: " + ex.getMessage());
            finishGitHubDeviceFlow(generation, null, ex.code, ex.getMessage());
        } catch (IOException ex) {
            // A network blip while the user is still on github.com must not
            // kill the sign-in; the deadline bounds the retries.
            Log.w(TAG, "GitHub sign-in poll failed; retrying", ex);
            scheduleGitHubPoll(generation, clientId, deviceCode, intervalSeconds, deadlineMillis);
        }
    }

    /** Saves (on success) and reports the outcome, unless the flow went stale meanwhile. */
    private void finishGitHubDeviceFlow(int generation, CloudTokenStore tokens, String errorCode, String message) {
        synchronized (githubDeviceLock) {
            if (generation != githubDeviceGeneration) {
                return;
            }
            githubDeviceGeneration++;
            if (tokens != null) {
                saveTokens(githubAuth, tokens);
            }
        }
        if (tokens != null) {
            Log.i(TAG, "Connected the GitHub account");
            emitAuthResult(githubAuth, true, tokens.accountName, null, null, null);
        } else {
            emitAuthResult(githubAuth, false, null, errorCode, message, null);
        }
    }

    private boolean isGitHubDeviceFlowCurrent(int generation) {
        synchronized (githubDeviceLock) {
            return generation == githubDeviceGeneration;
        }
    }

    private void cancelGitHubDeviceFlow() {
        synchronized (githubDeviceLock) {
            githubDeviceGeneration++;
        }
    }

    private ProviderAuth requireDocumentProvider(PluginCall call, String provider) {
        if (PROVIDER_WEBDAV.equals(provider)) {
            call.reject("WebDAV support is not available yet", WebDavCloudClient.NOT_IMPLEMENTED_CODE);
            return null;
        }
        ProviderAuth auth = providerAuthFor(provider);
        if (auth == null) {
            call.reject("Unknown cloud provider", "CLOUD_PROVIDER_UNKNOWN");
        }
        return auth;
    }

    private void maybeCompleteAuth(Intent intent) {
        if (intent == null || intent.getData() == null) {
            return;
        }
        Uri data = intent.getData();
        ProviderAuth auth;
        if (oneDriveAuth.matchesRedirect(data)) {
            auth = oneDriveAuth;
        } else if (googleDriveAuth.matchesRedirect(data)) {
            auth = googleDriveAuth;
        } else {
            return;
        }

        String pendingSerialized = cloudPrefs().getString(auth.pendingPrefKey, "");
        if (pendingSerialized.length() == 0) {
            return;
        }

        String expectedState;
        String verifier;
        try {
            JSONObject pending = new JSONObject(pendingSerialized);
            expectedState = pending.optString("state", "");
            verifier = pending.optString("verifier", "");
        } catch (JSONException ex) {
            cloudPrefs().edit().remove(auth.pendingPrefKey).apply();
            emitAuthResult(auth, false, null, "CLOUD_AUTH_FAILED",
                "The " + auth.label + " sign-in state was unreadable", null);
            return;
        }

        // The state parameter must match BEFORE the pending record is
        // consumed, on error responses too: the scheme is public, so any
        // app could fire a forged redirect (e.g. error=access_denied) to
        // wipe the in-flight verifier and deny the real sign-in. Redirects
        // that cannot prove they belong to our request are ignored.
        String state = data.getQueryParameter("state");
        if (expectedState.length() == 0 || !expectedState.equals(state)) {
            Log.w(TAG, "Ignored a " + auth.label + " redirect with a mismatched state");
            return;
        }
        // Consuming the record here also deduplicates delivery: the launch
        // intent can be observed again after handleOnNewIntent ran.
        cloudPrefs().edit().remove(auth.pendingPrefKey).apply();

        String error = data.getQueryParameter("error");
        if (error != null && error.length() > 0) {
            boolean canceled = "access_denied".equals(error);
            Log.w(TAG, auth.label + " sign-in was not completed: " + safeForLog(error));
            emitAuthResult(
                auth,
                false,
                null,
                canceled ? "CLOUD_AUTH_CANCELED" : "CLOUD_AUTH_FAILED",
                canceled
                    ? "The " + auth.label + " sign-in was canceled"
                    : "The " + auth.label + " sign-in failed",
                null
            );
            return;
        }

        String code = data.getQueryParameter("code");
        if (code == null || code.length() == 0) {
            emitAuthResult(auth, false, null, "CLOUD_AUTH_FAILED",
                "The " + auth.label + " sign-in response was invalid", null);
            return;
        }

        // Google's in-flow picker (trigger_onepick) appends the selection
        // to the same redirect; empty on plain sign-ins and for OneDrive.
        String pickedSerialized = data.getQueryParameter("picked_file_ids");
        JSArray pickedFileIds = new JSArray();
        if (pickedSerialized != null && pickedSerialized.length() > 0) {
            for (String pickedId : pickedSerialized.split(",")) {
                if (pickedId.trim().length() > 0) {
                    pickedFileIds.put(pickedId.trim());
                }
            }
        }

        // The redirect is real and its token exchange is about to run in the
        // background: tell the web layer NOW, so the sign-in button locks
        // into a "completing" state instead of reviving when the app merely
        // becomes visible again (the visibility signal cannot distinguish
        // "backed out of the browser" from "redirect landed, exchanging").
        auth.exchangeInFlight = true;
        JSObject pendingEvent = new JSObject();
        pendingEvent.put("provider", auth.id);
        notifyListeners(EVENT_AUTH_PENDING, pendingEvent, true);

        cloudExecutor.execute(() -> {
            try {
                CloudTokenStore exchanged = auth.exchangeCode(code, verifier, System.currentTimeMillis());
                String accountName = null;
                try {
                    accountName = auth.fetchAccountName(exchanged.accessToken);
                } catch (CloudProviderException | IOException ex) {
                    // The account label is cosmetic; the connection still works.
                    Log.w(TAG, "Could not read the " + auth.label + " account name", ex);
                }
                saveTokens(auth, exchanged.withAccountName(accountName));
                Log.i(TAG, "Connected the " + auth.label + " account"
                    + (pickedFileIds.length() > 0 ? " with a picked document" : ""));
                emitAuthResult(auth, true, accountName, null, null, pickedFileIds);
            } catch (CloudProviderException ex) {
                Log.w(TAG, auth.label + " sign-in completion failed: " + ex.getMessage());
                emitAuthResult(auth, false, null, ex.code, ex.getMessage(), null);
            } catch (IOException ex) {
                Log.w(TAG, auth.label + " sign-in completion failed", ex);
                emitAuthResult(auth, false, null, "CLOUD_NETWORK_FAILED",
                    "Could not reach " + auth.label, null);
            }
        });
    }

    private void emitAuthResult(
        ProviderAuth auth,
        boolean connected,
        String accountName,
        String errorCode,
        String message,
        JSArray pickedFileIds
    ) {
        // Every exchange outcome flows through here; releasing the gate
        // before the event keeps connect/disconnect available the moment
        // the web layer learns the result.
        auth.exchangeInFlight = false;
        JSObject event = new JSObject();
        event.put("provider", auth.id);
        event.put("connected", connected);
        event.put("accountName", accountName == null ? JSObject.NULL : accountName);
        if (pickedFileIds != null && pickedFileIds.length() > 0) {
            event.put("pickedFileIds", pickedFileIds);
        }
        if (errorCode != null) {
            event.put("errorCode", errorCode);
            event.put("message", message);
        }
        notifyListeners(EVENT_AUTH_COMPLETED, event, true);
    }

    private CloudTokenStore requireFreshTokens(ProviderAuth auth) throws CloudProviderException, IOException {
        CloudTokenStore tokens = readTokens(auth);
        if (tokens == null) {
            throw new CloudProviderException(
                "CLOUD_NOT_CONNECTED",
                "No " + auth.label + " account is connected"
            );
        }
        if (tokens.isAccessTokenFresh(System.currentTimeMillis())) {
            return tokens;
        }

        try {
            CloudTokenStore refreshed = auth.refresh(tokens, System.currentTimeMillis());
            saveTokens(auth, refreshed);
            return refreshed;
        } catch (CloudProviderException ex) {
            if ("CLOUD_AUTH_EXPIRED".equals(ex.code)) {
                // The refresh token is dead; drop it so the account state
                // honestly reads disconnected.
                cloudPrefs().edit().remove(auth.tokensPrefKey).apply();
            }
            throw ex;
        }
    }

    private CloudTokenStore readTokens(ProviderAuth auth) {
        return CloudTokenStore.parse(cloudPrefs().getString(auth.tokensPrefKey, ""));
    }

    private void saveTokens(ProviderAuth auth, CloudTokenStore tokens) {
        cloudPrefs().edit().putString(auth.tokensPrefKey, tokens.serialize()).apply();
    }

    private SharedPreferences cloudPrefs() {
        return getContext().getSharedPreferences(CLOUD_PREFS, Context.MODE_PRIVATE);
    }

    private String safeForLog(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\r', ' ').replace('\n', ' ').trim();
    }

}

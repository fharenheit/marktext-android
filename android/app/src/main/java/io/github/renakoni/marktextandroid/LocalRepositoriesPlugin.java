package io.github.renakoni.marktextandroid;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.json.JSONObject;

/**
 * Git working copies on the device (clone → edit files → commit → push,
 * pull with conflict resolution, branches, history, diffs, reverts). The
 * Git semantics live in the pure-JVM {@link LocalGitRepository}; this
 * plugin owns storage (app-private files/repositories/<id>), credentials
 * (the GitHub sign-in token over HTTPS), threading (one Git operation at a
 * time) and the bridge shapes.
 *
 * Documents inside a working copy are read and written here as ordinary
 * files, so editing autosaves to the working tree and only an explicit
 * commit records history.
 */
@CapacitorPlugin(name = "LocalRepositories")
public class LocalRepositoriesPlugin extends Plugin {

    private static final String TAG = "MarkTextAndroid";
    /** Per-host username/password for non-GitHub remotes (GitCredentialStore JSON). */
    private static final String GIT_CREDENTIAL_PREFS = "git_credentials";
    private static final String REPOSITORIES_DIRECTORY = "repositories";
    private static final String GIT_CONFIG_DIRECTORY = "git-config";
    private static final String IMPORTED_IMAGE_DIRECTORY = "images";
    private static final String EVENT_PROGRESS = "repositoryProgress";
    private static final int MAX_IMAGE_BYTES = 15 * 1024 * 1024;

    private final ExecutorService gitExecutor = Executors.newSingleThreadExecutor();
    private final CharsetSniffer charsetSniffer = new IcuCharsetSniffer();

    /** One Git operation; the plugin maps its failures onto the call. */
    private interface GitTask {
        JSObject run() throws LocalRepositoryException, DocumentReadException, IOException;
    }

    @Override
    public void load() {
        super.load();
        SandboxedGitSystemReader.install(new File(getContext().getFilesDir(), GIT_CONFIG_DIRECTORY));
    }

    @PluginMethod
    public void listRepositories(PluginCall call) {
        run(call, () -> {
            JSArray values = new JSArray();
            File[] directories = repositoriesDirectory().listFiles();
            if (directories != null) {
                List<File> sorted = new ArrayList<>();
                Collections.addAll(sorted, directories);
                Collections.sort(sorted, (left, right) -> left.getName().compareToIgnoreCase(right.getName()));
                for (File directory : sorted) {
                    if (!new File(directory, ".git").isDirectory()) {
                        continue;
                    }
                    try (LocalGitRepository repository = LocalGitRepository.open(directory)) {
                        values.put(describe(directory.getName(), repository));
                    } catch (LocalRepositoryException ex) {
                        Log.w(TAG, "Skipped an unreadable repository: " + directory.getName());
                    }
                }
            }
            JSObject result = new JSObject();
            result.put("repositories", values);
            return result;
        });
    }

    /**
     * Clones into a new working copy. `url` is any HTTPS Git URL; GitHub
     * URLs use the signed-in GitHub account. Other hosts take an optional
     * `username`/`password` (or token): an empty password reuses the one
     * remembered for that host and username, and credentials that cloned
     * successfully are remembered for later clones, pushes and pulls.
     */
    @PluginMethod
    public void cloneRepository(PluginCall call) {
        String url = call.getString("url", "").trim();
        String branch = call.getString("branch", "").trim();
        String requestedName = call.getString("name", "").trim();
        String username = call.getString("username", "").trim();
        String password = call.getString("password", "");
        boolean remember = Boolean.TRUE.equals(call.getBoolean("remember", true));
        if (!url.startsWith("https://") || Uri.parse(url).getHost() == null) {
            call.reject("Use an https:// repository URL", "INVALID_REMOTE_URL");
            return;
        }
        String hostKey = GitCredentialStore.hostKey(url);
        GitCredentialStore typed = null;
        if (!GitCredentialStore.isGitHubHost(hostKey) && username.length() > 0) {
            GitCredentialStore saved = readGitCredentials(hostKey);
            if (password.length() == 0 && saved != null && username.equals(saved.username)) {
                password = saved.password;
            }
            typed = new GitCredentialStore(username, password);
        }
        GitCredentialStore credentials = typed;
        if (branch.length() > 0 && !GitHubClient.isValidBranchName(branch)) {
            call.reject("This branch name is not valid", "INVALID_BRANCH_NAME");
            return;
        }
        String name = requestedName.length() > 0 ? requestedName : nameFromUrl(url);
        run(call, () -> {
            String id = uniqueId(name);
            File directory = new File(repositoriesDirectory(), id);
            Log.i(TAG, "Cloning repository " + id);
            try (LocalGitRepository repository = LocalGitRepository.cloneInto(
                directory,
                url,
                branch,
                credentials != null
                    ? new UsernamePasswordCredentialsProvider(credentials.username, credentials.password)
                    : credentialsFor(url),
                (task, completed, total) -> emitProgress(id, task, completed, total)
            )) {
                if (credentials != null && remember) {
                    saveGitCredentials(hostKey, credentials);
                }
                setDisplayName(repository, name);
                applyIdentity(repository);
                return describe(id, repository);
            }
        });
    }

    /** What the clone form may prefill for a host: the username, never the password. */
    @PluginMethod
    public void getGitCredentials(PluginCall call) {
        String hostKey = GitCredentialStore.hostKey(call.getString("url", ""));
        GitCredentialStore saved = hostKey == null ? null : readGitCredentials(hostKey);
        JSObject result = new JSObject();
        result.put("host", hostKey == null ? JSObject.NULL : hostKey);
        result.put("username", saved == null ? JSObject.NULL : saved.username);
        result.put("hasPassword", saved != null && saved.password.length() > 0);
        call.resolve(result);
    }

    @PluginMethod
    public void forgetGitCredentials(PluginCall call) {
        String hostKey = GitCredentialStore.hostKey(call.getString("url", ""));
        if (hostKey != null) {
            gitCredentialPrefs().edit().remove(hostKey).apply();
        }
        call.resolve(new JSObject());
    }

    @PluginMethod
    public void deleteRepository(PluginCall call) {
        String id = call.getString("id", "");
        run(call, () -> {
            File directory = repositoryDirectory(id);
            LocalGitRepository.deleteRecursively(directory);
            Log.i(TAG, "Deleted repository " + id);
            return new JSObject();
        });
    }

    @PluginMethod
    public void getRepositoryStatus(PluginCall call) {
        withRepository(call, repository -> statusJson(repository.status()));
    }

    @PluginMethod
    public void listRepositoryFolder(PluginCall call) {
        String path = call.getString("path", "");
        withRepository(call, repository -> {
            JSArray values = new JSArray();
            for (LocalGitRepository.Entry entry : repository.listFolder(path)) {
                JSObject value = new JSObject();
                value.put("name", entry.name);
                value.put("path", entry.path);
                value.put("isFolder", entry.isFolder);
                value.put("size", entry.size);
                values.put(value);
            }
            JSObject result = new JSObject();
            result.put("entries", values);
            return result;
        });
    }

    @PluginMethod
    public void readRepositoryDocument(PluginCall call) {
        String id = call.getString("id", "");
        String path = call.getString("path", "");
        String defaultEncoding = MarkdownCodec.normalizeEncoding(call.getString("defaultEncoding", "utf8"));
        boolean autoDetectEncoding = call.getBoolean("autoDetectEncoding", true);
        withRepository(call, repository -> {
            byte[] bytes = repository.readFile(path, MarkdownCodec.MAX_MARKDOWN_BYTES);
            DecodedMarkdown decoded = MarkdownCodec.decode(bytes, defaultEncoding, autoDetectEncoding, charsetSniffer);
            JSObject result = new JSObject();
            result.put("path", path);
            result.put("displayName", path.substring(path.lastIndexOf('/') + 1));
            result.put("repositoryName", displayName(id, repository));
            result.put("workTreeUri", Uri.fromFile(repository.getWorkTree()).toString());
            result.put("markdown", decoded.markdown);
            result.put("encoding", MarkdownCodec.normalizeEncoding(decoded.encoding));
            result.put("hasEncodingBom", decoded.hasBom);
            return result;
        });
    }

    /**
     * Saves a document into the working tree. Images inserted on the device
     * (`localImages`: imported-image file names with their repository
     * paths) are copied into the working tree too, so they get committed
     * with the document.
     */
    @PluginMethod
    public void writeRepositoryDocument(PluginCall call) {
        String path = call.getString("path", "");
        String markdown = call.getString("markdown", null);
        String encoding = MarkdownCodec.normalizeEncoding(call.getString("encoding", "utf8"));
        boolean writeBom = call.getBoolean("writeBom", false);
        JSArray localImages = call.getArray("localImages", new JSArray());
        if (markdown == null) {
            call.reject("Markdown content is required", "INVALID_MARKDOWN");
            return;
        }
        withRepository(call, repository -> {
            byte[] bytes = MarkdownCodec.validateBytes(markdown, new MarkdownWriteOptions(encoding, writeBom));
            copyLocalImages(repository, localImages);
            repository.writeFile(path, bytes);
            JSObject result = new JSObject();
            result.put("path", path);
            result.put("displayName", path.substring(path.lastIndexOf('/') + 1));
            return result;
        });
    }

    /** The committed (HEAD) and current text of one file, for a diff. */
    @PluginMethod
    public void getRepositoryFileDiff(PluginCall call) {
        String path = call.getString("path", "");
        withRepository(call, repository -> {
            byte[] committed = repository.readCommittedFile(path);
            File file = repository.resolve(path);
            byte[] current = file.isFile() && file.length() <= LocalGitRepository.MAX_DIFF_BYTES
                ? Files.readAllBytes(file.toPath())
                : null;
            boolean tooLarge = file.isFile() && current == null;
            JSObject result = new JSObject();
            result.put("path", path);
            putTextSides(result, committed, current, tooLarge);
            return result;
        });
    }

    @PluginMethod
    public void commitRepository(PluginCall call) {
        String message = call.getString("message", "").trim();
        if (message.length() == 0) {
            call.reject("A commit message is required", "INVALID_COMMIT_MESSAGE");
            return;
        }
        withRepository(call, repository -> {
            applyIdentity(repository);
            return commitJson(repository.commitAll(message));
        });
    }

    @PluginMethod
    public void pushRepository(PluginCall call) {
        String id = call.getString("id", "");
        withRepository(call, repository -> {
            repository.push(
                credentialsFor(repository.getRemoteUrl()),
                (task, completed, total) -> emitProgress(id, task, completed, total)
            );
            return statusJson(repository.status());
        });
    }

    @PluginMethod
    public void pullRepository(PluginCall call) {
        String id = call.getString("id", "");
        withRepository(call, repository -> {
            applyIdentity(repository);
            LocalGitRepository.PullOutcome outcome = repository.pull(
                credentialsFor(repository.getRemoteUrl()),
                (task, completed, total) -> emitProgress(id, task, completed, total)
            );
            return outcomeJson(outcome, repository);
        });
    }

    @PluginMethod
    public void resolveRepositoryConflict(PluginCall call) {
        String path = call.getString("path", "");
        String choice = call.getString("choice", "");
        withRepository(call, repository -> {
            repository.resolveConflict(path, choice);
            return statusJson(repository.status());
        });
    }

    @PluginMethod
    public void completeRepositoryMerge(PluginCall call) {
        String message = call.getString("message", "");
        withRepository(call, repository -> {
            applyIdentity(repository);
            return commitJson(repository.completeMerge(message));
        });
    }

    @PluginMethod
    public void abortRepositoryMerge(PluginCall call) {
        withRepository(call, repository -> {
            repository.abortMerge();
            return statusJson(repository.status());
        });
    }

    /** Reverts uncommitted edits; an empty `paths` list reverts everything. */
    @PluginMethod
    public void discardRepositoryChanges(PluginCall call) {
        JSArray values = call.getArray("paths", new JSArray());
        List<String> paths = new ArrayList<>();
        for (int index = 0; index < values.length(); index++) {
            String path = values.optString(index, "");
            if (path.length() > 0) {
                paths.add(path);
            }
        }
        withRepository(call, repository -> {
            repository.discardChanges(paths);
            return statusJson(repository.status());
        });
    }

    @PluginMethod
    public void listRepositoryBranches(PluginCall call) {
        withRepository(call, repository -> {
            JSArray values = new JSArray();
            for (LocalGitRepository.BranchInfo branch : repository.branches()) {
                JSObject value = new JSObject();
                value.put("name", branch.name);
                value.put("current", branch.current);
                value.put("local", branch.local);
                value.put("remote", branch.remote);
                values.put(value);
            }
            JSObject result = new JSObject();
            result.put("branches", values);
            return result;
        });
    }

    @PluginMethod
    public void checkoutRepositoryBranch(PluginCall call) {
        String name = call.getString("name", "");
        boolean create = call.getBoolean("create", false);
        withRepository(call, repository -> {
            if (create) {
                repository.createBranch(name);
            } else {
                repository.checkoutBranch(name);
            }
            return statusJson(repository.status());
        });
    }

    @PluginMethod
    public void getRepositoryHistory(PluginCall call) {
        int maxCount = Math.max(1, Math.min(200, call.getInt("maxCount", 50)));
        int skip = Math.max(0, call.getInt("skip", 0));
        withRepository(call, repository -> {
            JSArray values = new JSArray();
            for (LocalGitRepository.CommitInfo commit : repository.log(maxCount, skip)) {
                values.put(commitJson(commit));
            }
            JSObject result = new JSObject();
            result.put("commits", values);
            return result;
        });
    }

    @PluginMethod
    public void getRepositoryCommitDiff(PluginCall call) {
        String sha = call.getString("sha", "");
        if (!sha.matches("[0-9a-f]{7,40}")) {
            call.reject("A commit id is required", "COMMIT_NOT_FOUND");
            return;
        }
        withRepository(call, repository -> {
            JSArray files = new JSArray();
            for (LocalGitRepository.FileDiff diff : repository.commitDiff(sha)) {
                JSObject value = new JSObject();
                value.put("changeType", diff.changeType);
                value.put("oldPath", diff.oldPath == null ? JSObject.NULL : diff.oldPath);
                value.put("newPath", diff.newPath == null ? JSObject.NULL : diff.newPath);
                value.put("binary", diff.binary);
                putTextSides(value, diff.oldBytes, diff.newBytes, diff.tooLarge);
                files.put(value);
            }
            JSObject result = new JSObject();
            result.put("files", files);
            return result;
        });
    }

    @PluginMethod
    public void revertRepositoryCommit(PluginCall call) {
        String sha = call.getString("sha", "");
        if (!sha.matches("[0-9a-f]{7,40}")) {
            call.reject("A commit id is required", "COMMIT_NOT_FOUND");
            return;
        }
        withRepository(call, repository -> {
            applyIdentity(repository);
            return outcomeJson(repository.revertCommit(sha), repository);
        });
    }

    // --- plumbing --------------------------------------------------------

    private interface RepositoryTask {
        JSObject run(LocalGitRepository repository) throws LocalRepositoryException, DocumentReadException, IOException;
    }

    private void withRepository(PluginCall call, RepositoryTask task) {
        String id = call.getString("id", "");
        run(call, () -> {
            try (LocalGitRepository repository = LocalGitRepository.open(repositoryDirectory(id))) {
                return task.run(repository);
            }
        });
    }

    private void run(PluginCall call, GitTask task) {
        gitExecutor.execute(() -> {
            try {
                call.resolve(task.run());
            } catch (LocalRepositoryException ex) {
                Log.w(TAG, "Repository operation failed: " + ex.code + " " + ex.getMessage());
                JSObject data = new JSObject();
                JSArray paths = new JSArray();
                for (String path : ex.paths) {
                    paths.put(path);
                }
                data.put("paths", paths);
                call.reject(ex.getMessage(), ex.code, ex, data);
            } catch (DocumentReadException ex) {
                call.reject(ex.getMessage(), ex.code, ex);
            } catch (IOException | RuntimeException ex) {
                Log.e(TAG, "Repository operation failed", ex);
                call.reject("The repository operation failed", "REPOSITORY_FAILED", ex);
            }
        });
    }

    private File repositoriesDirectory() {
        File directory = new File(getContext().getFilesDir(), REPOSITORIES_DIRECTORY);
        //noinspection ResultOfMethodCallIgnored
        directory.mkdirs();
        return directory;
    }

    private File repositoryDirectory(String id) throws LocalRepositoryException {
        if (!isValidId(id)) {
            throw new LocalRepositoryException("REPOSITORY_NOT_FOUND", "This repository is not on the device anymore");
        }
        File directory = new File(repositoriesDirectory(), id);
        if (!new File(directory, ".git").isDirectory()) {
            throw new LocalRepositoryException("REPOSITORY_NOT_FOUND", "This repository is not on the device anymore");
        }
        return directory;
    }

    static boolean isValidId(String id) {
        return id != null && id.matches("[a-z0-9][a-z0-9._-]{0,79}") && !id.contains("..");
    }

    /** owner-repo for GitHub-style URLs, else the last path segment. */
    static String nameFromUrl(String url) {
        String path = Uri.parse(url).getPath();
        if (path == null) {
            return "repository";
        }
        String trimmed = path.replaceAll("^/+|/+$", "").replaceAll("\\.git$", "");
        return trimmed.length() == 0 ? "repository" : trimmed;
    }

    static String slug(String name) {
        String slug = name.toLowerCase(Locale.US).replaceAll("[^a-z0-9._-]+", "-")
            .replaceAll("\\.{2,}", ".").replaceAll("^[^a-z0-9]+|-+$", "");
        if (slug.length() > 60) {
            slug = slug.substring(0, 60);
        }
        return slug.length() == 0 ? "repository" : slug;
    }

    private String uniqueId(String name) {
        String base = slug(name);
        String id = base;
        for (int suffix = 2; new File(repositoriesDirectory(), id).exists(); suffix++) {
            id = base + "-" + suffix;
        }
        return id;
    }

    private static void setDisplayName(LocalGitRepository repository, String name) throws LocalRepositoryException {
        repository.setConfigValue("marktext", "name", name);
    }

    private static String displayName(String id, LocalGitRepository repository) {
        String name = repository.getConfigValue("marktext", "name");
        return name == null || name.length() == 0 ? id : name;
    }

    private JSObject describe(String id, LocalGitRepository repository) throws LocalRepositoryException {
        LocalGitRepository.StatusSummary status = repository.status();
        JSObject value = new JSObject();
        value.put("id", id);
        value.put("name", displayName(id, repository));
        value.put("remoteUrl", repository.getRemoteUrl() == null ? JSObject.NULL : repository.getRemoteUrl());
        value.put("branch", status.branch);
        value.put("changeCount", status.changes.size());
        value.put("ahead", status.ahead);
        value.put("behind", status.behind);
        value.put("state", status.state);
        // file:// root of the working tree; the web layer converts it for
        // the WebView to show the repository's own images.
        value.put("workTreeUri", Uri.fromFile(repository.getWorkTree()).toString());
        return value;
    }

    private static JSObject statusJson(LocalGitRepository.StatusSummary status) {
        JSObject result = new JSObject();
        result.put("branch", status.branch);
        result.put("state", status.state);
        result.put("ahead", status.ahead);
        result.put("behind", status.behind);
        result.put("hasUpstream", status.hasUpstream);
        JSArray changes = new JSArray();
        for (LocalGitRepository.FileChange change : status.changes) {
            JSObject value = new JSObject();
            value.put("path", change.path);
            value.put("kind", change.kind);
            changes.put(value);
        }
        result.put("changes", changes);
        return result;
    }

    private static JSObject commitJson(LocalGitRepository.CommitInfo commit) {
        JSObject value = new JSObject();
        value.put("sha", commit.sha);
        value.put("shortSha", commit.shortSha);
        value.put("summary", commit.summary);
        value.put("message", commit.message);
        value.put("author", commit.author);
        value.put("date", commit.date);
        value.put("parentCount", commit.parentCount);
        return value;
    }

    private static JSObject outcomeJson(LocalGitRepository.PullOutcome outcome, LocalGitRepository repository)
        throws LocalRepositoryException {
        JSObject result = new JSObject();
        result.put("result", outcome.result);
        JSArray conflicts = new JSArray();
        for (String path : outcome.conflicts) {
            conflicts.put(path);
        }
        result.put("conflicts", conflicts);
        result.put("status", statusJson(repository.status()));
        return result;
    }

    /** Text sides for the web diff; binary or oversized sides are flagged, not sent. */
    private static void putTextSides(JSObject value, byte[] oldBytes, byte[] newBytes, boolean tooLarge) {
        boolean binary = (oldBytes != null && RawText.isBinary(oldBytes))
            || (newBytes != null && RawText.isBinary(newBytes));
        value.put("binary", value.optBoolean("binary", false) || binary);
        value.put("tooLarge", tooLarge);
        value.put("oldText", oldBytes == null || binary ? JSObject.NULL : new String(oldBytes, StandardCharsets.UTF_8));
        value.put("newText", newBytes == null || binary ? JSObject.NULL : new String(newBytes, StandardCharsets.UTF_8));
    }

    private void copyLocalImages(LocalGitRepository repository, JSArray values)
        throws LocalRepositoryException, IOException {
        File directory = new File(getContext().getFilesDir(), IMPORTED_IMAGE_DIRECTORY);
        for (int index = 0; index < values.length(); index++) {
            JSONObject value = values.optJSONObject(index);
            String name = value == null ? "" : value.optString("name", "");
            String path = value == null ? "" : value.optString("path", "");
            if (!name.matches("[A-Za-z0-9_-][A-Za-z0-9._-]*") || path.length() == 0) {
                throw new LocalRepositoryException("INVALID_IMAGE_RESULT", "An image has an invalid name");
            }
            File source = new File(directory, name);
            if (!source.isFile()) {
                throw new LocalRepositoryException("IMAGE_NOT_FOUND", "An image in this document is missing on this device");
            }
            if (source.length() > MAX_IMAGE_BYTES) {
                throw new LocalRepositoryException("IMAGE_TOO_LARGE", "An image in this document is larger than 15 MB");
            }
            File target = repository.resolve(path);
            if (target.isFile() && target.length() == source.length()) {
                continue;
            }
            repository.writeFile(path, Files.readAllBytes(source.toPath()));
        }
    }

    /**
     * The signed-in GitHub token for github.com remotes; the remembered
     * username/password for other hosts; anonymous otherwise.
     */
    private CredentialsProvider credentialsFor(String url) {
        String hostKey = GitCredentialStore.hostKey(url);
        if (hostKey == null) {
            return null;
        }
        if (!GitCredentialStore.isGitHubHost(hostKey)) {
            GitCredentialStore saved = readGitCredentials(hostKey);
            return saved == null ? null : new UsernamePasswordCredentialsProvider(saved.username, saved.password);
        }
        CloudTokenStore tokens = readGitHubTokens();
        if (tokens == null || !tokens.isAccessTokenFresh(System.currentTimeMillis())) {
            return null;
        }
        return new UsernamePasswordCredentialsProvider("x-access-token", tokens.accessToken);
    }

    /** Commits are authored as the GitHub account (its no-reply address) when signed in. */
    private void applyIdentity(LocalGitRepository repository) throws LocalRepositoryException {
        CloudTokenStore tokens = readGitHubTokens();
        String login = tokens == null ? null : tokens.accountName;
        if (login != null && login.matches("[A-Za-z0-9-]+")) {
            repository.setIdentity(login, login + "@users.noreply.github.com");
        } else {
            repository.setIdentity("MarkText for Android", "marktext@users.noreply.github.com");
        }
    }

    private android.content.SharedPreferences gitCredentialPrefs() {
        return getContext().getSharedPreferences(GIT_CREDENTIAL_PREFS, Context.MODE_PRIVATE);
    }

    private GitCredentialStore readGitCredentials(String hostKey) {
        return GitCredentialStore.parse(gitCredentialPrefs().getString(hostKey, ""));
    }

    private void saveGitCredentials(String hostKey, GitCredentialStore credentials) {
        gitCredentialPrefs().edit().putString(hostKey, credentials.serialize()).apply();
    }

    private CloudTokenStore readGitHubTokens() {
        return CloudTokenStore.parse(
            getContext()
                .getSharedPreferences(CloudDocumentsPlugin.CLOUD_PREFS, Context.MODE_PRIVATE)
                .getString(CloudDocumentsPlugin.PREF_GITHUB_TOKENS, "")
        );
    }

    private long lastProgressMillis = 0;

    private void emitProgress(String id, String task, int completed, int total) {
        long now = System.currentTimeMillis();
        boolean finished = total > 0 && completed >= total;
        if (!finished && now - lastProgressMillis < 250) {
            return;
        }
        lastProgressMillis = now;
        JSObject event = new JSObject();
        event.put("id", id);
        event.put("task", task);
        event.put("completed", completed);
        event.put("total", total);
        notifyListeners(EVENT_PROGRESS, event);
    }
}

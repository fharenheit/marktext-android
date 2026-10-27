package io.github.renakoni.marktextandroid;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.CheckoutConflictException;
import org.eclipse.jgit.api.errors.EmptyCommitException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidConfigurationException;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.api.errors.RefAlreadyExistsException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.api.errors.UnmergedPathsException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.BranchTrackingStatus;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.io.DisabledOutputStream;

/**
 * A Git working copy on the device, pure JVM over JGit so every operation
 * is testable against local bare repositories. The plugin owns storage,
 * credentials and threading; this class owns the Git semantics:
 * clone, status, commit, push, pull (merge) with conflict resolution,
 * discarding edits, branches, history, commit diffs and reverts.
 *
 * Paths are always repository-relative with '/' separators and are
 * validated to stay inside the working tree and out of .git.
 */
final class LocalGitRepository implements AutoCloseable {

    static final String REMOTE = "origin";
    /** Text sides of a diff larger than this are reported as too large. */
    static final int MAX_DIFF_BYTES = 1024 * 1024;

    /** Receives clone/fetch/push progress; may be called often. */
    interface ProgressListener {
        void onProgress(String task, int completed, int total);
    }

    static final class FileChange {

        /** added, modified, deleted, conflicting */
        final String kind;
        final String path;

        FileChange(String kind, String path) {
            this.kind = kind;
            this.path = path;
        }
    }

    static final class StatusSummary {

        final String branch;
        /** SAFE, MERGING, REVERTING (with "_RESOLVED" once nothing conflicts). */
        final String state;
        final int ahead;
        final int behind;
        final boolean hasUpstream;
        final List<FileChange> changes;

        StatusSummary(String branch, String state, int ahead, int behind, boolean hasUpstream, List<FileChange> changes) {
            this.branch = branch;
            this.state = state;
            this.ahead = ahead;
            this.behind = behind;
            this.hasUpstream = hasUpstream;
            this.changes = changes;
        }
    }

    static final class Entry {

        final String name;
        final String path;
        final boolean isFolder;
        final long size;

        Entry(String name, String path, boolean isFolder, long size) {
            this.name = name;
            this.path = path;
            this.isFolder = isFolder;
            this.size = size;
        }
    }

    /** Both sides of one file's change; null side = absent, binary/oversized flagged. */
    static final class FileDiff {

        final String changeType;
        final String oldPath;
        final String newPath;
        final byte[] oldBytes;
        final byte[] newBytes;
        final boolean binary;
        final boolean tooLarge;

        FileDiff(String changeType, String oldPath, String newPath, byte[] oldBytes, byte[] newBytes,
                 boolean binary, boolean tooLarge) {
            this.changeType = changeType;
            this.oldPath = oldPath;
            this.newPath = newPath;
            this.oldBytes = oldBytes;
            this.newBytes = newBytes;
            this.binary = binary;
            this.tooLarge = tooLarge;
        }
    }

    static final class CommitInfo {

        final String sha;
        final String shortSha;
        final String summary;
        final String message;
        final String author;
        final String date;
        final int parentCount;

        CommitInfo(String sha, String shortSha, String summary, String message, String author, String date,
                   int parentCount) {
            this.sha = sha;
            this.shortSha = shortSha;
            this.summary = summary;
            this.message = message;
            this.author = author;
            this.date = date;
            this.parentCount = parentCount;
        }
    }

    static final class BranchInfo {

        final String name;
        final boolean current;
        final boolean local;
        /** Remote counterpart exists (origin/name). */
        final boolean remote;

        BranchInfo(String name, boolean current, boolean local, boolean remote) {
            this.name = name;
            this.current = current;
            this.local = local;
            this.remote = remote;
        }
    }

    /** up-to-date, fast-forward, merged, conflicts */
    static final class PullOutcome {

        final String result;
        final List<String> conflicts;

        PullOutcome(String result, List<String> conflicts) {
            this.result = result;
            this.conflicts = conflicts;
        }
    }

    private final Git git;
    private final File workTree;

    private LocalGitRepository(Git git) {
        this.git = git;
        this.workTree = git.getRepository().getWorkTree();
    }

    static LocalGitRepository open(File directory) throws LocalRepositoryException {
        try {
            return new LocalGitRepository(Git.open(directory));
        } catch (IOException ex) {
            throw new LocalRepositoryException("REPOSITORY_NOT_FOUND", "This repository is not on the device anymore", ex);
        }
    }

    static LocalGitRepository cloneInto(
        File directory,
        String url,
        String branch,
        CredentialsProvider credentials,
        ProgressListener progress
    ) throws LocalRepositoryException {
        try {
            org.eclipse.jgit.api.CloneCommand clone = Git.cloneRepository()
                .setURI(url)
                .setDirectory(directory)
                .setRemote(REMOTE)
                .setCredentialsProvider(credentials)
                .setProgressMonitor(monitor(progress));
            if (branch != null && branch.length() > 0) {
                clone.setBranch(Constants.R_HEADS + branch);
            }
            return new LocalGitRepository(clone.call());
        } catch (GitAPIException ex) {
            deleteRecursively(directory);
            throw translate(ex, "CLONE_FAILED", "The repository could not be cloned");
        } catch (RuntimeException ex) {
            deleteRecursively(directory);
            throw new LocalRepositoryException("CLONE_FAILED", "The repository could not be cloned", ex);
        }
    }

    File getWorkTree() {
        return workTree;
    }

    String getRemoteUrl() {
        return git.getRepository().getConfig().getString("remote", REMOTE, "url");
    }

    String getConfigValue(String section, String name) {
        return git.getRepository().getConfig().getString(section, null, name);
    }

    void setConfigValue(String section, String name, String value) throws LocalRepositoryException {
        StoredConfig config = git.getRepository().getConfig();
        config.setString(section, null, name, value);
        try {
            config.save();
        } catch (IOException ex) {
            throw new LocalRepositoryException("REPOSITORY_WRITE_FAILED", "The repository settings could not be saved", ex);
        }
    }

    /** Commits and merges are authored as this identity (stored in the repository config). */
    void setIdentity(String name, String email) throws LocalRepositoryException {
        StoredConfig config = git.getRepository().getConfig();
        config.setString("user", null, "name", name);
        config.setString("user", null, "email", email);
        try {
            config.save();
        } catch (IOException ex) {
            throw new LocalRepositoryException("REPOSITORY_WRITE_FAILED", "The repository settings could not be saved", ex);
        }
    }

    StatusSummary status() throws LocalRepositoryException {
        try {
            Repository repository = git.getRepository();
            Status status = git.status().call();
            Map<String, String> kinds = new LinkedHashMap<>();
            for (String path : status.getUntracked()) {
                kinds.put(path, "added");
            }
            for (String path : status.getAdded()) {
                kinds.put(path, "added");
            }
            for (String path : union(status.getModified(), status.getChanged())) {
                kinds.put(path, "modified");
            }
            for (String path : union(status.getMissing(), status.getRemoved())) {
                kinds.put(path, "deleted");
            }
            for (String path : status.getConflicting()) {
                kinds.put(path, "conflicting");
            }
            List<String> paths = new ArrayList<>(kinds.keySet());
            Collections.sort(paths, String.CASE_INSENSITIVE_ORDER);
            List<FileChange> changes = new ArrayList<>();
            for (String path : paths) {
                changes.add(new FileChange(kinds.get(path), path));
            }

            String branch = repository.getBranch();
            BranchTrackingStatus tracking = branch == null ? null : BranchTrackingStatus.of(repository, branch);
            return new StatusSummary(
                branch == null ? "" : branch,
                stateName(repository.getRepositoryState()),
                tracking == null ? 0 : tracking.getAheadCount(),
                tracking == null ? 0 : tracking.getBehindCount(),
                tracking != null,
                changes
            );
        } catch (GitAPIException | IOException ex) {
            throw failure("STATUS_FAILED", "The repository status could not be read", ex);
        }
    }

    /** Folders plus Markdown candidates of one working-tree folder ("" = root). */
    List<Entry> listFolder(String folder) throws LocalRepositoryException {
        File directory = folder.length() == 0 ? workTree : resolve(folder);
        File[] files = directory.listFiles();
        List<Entry> entries = new ArrayList<>();
        if (files == null) {
            return entries;
        }
        for (File file : files) {
            String name = file.getName();
            if (name.startsWith(".")) {
                continue;
            }
            String path = folder.length() == 0 ? name : folder + "/" + name;
            if (file.isDirectory()) {
                entries.add(new Entry(name, path, true, 0));
            } else if (OneDriveGraphClient.isListableDocumentName(name)) {
                entries.add(new Entry(name, path, false, file.length()));
            }
        }
        Collections.sort(entries, (left, right) -> left.isFolder != right.isFolder
            ? (left.isFolder ? -1 : 1)
            : left.name.compareToIgnoreCase(right.name));
        return entries;
    }

    byte[] readFile(String path, int maxBytes) throws LocalRepositoryException {
        File file = resolve(path);
        if (!file.isFile()) {
            throw new LocalRepositoryException("DOCUMENT_NOT_FOUND", "This file is not in the repository anymore");
        }
        if (file.length() > maxBytes) {
            throw new LocalRepositoryException("DOCUMENT_TOO_LARGE", "This file is larger than the current limit");
        }
        try {
            return Files.readAllBytes(file.toPath());
        } catch (IOException ex) {
            throw new LocalRepositoryException("DOCUMENT_READ_FAILED", "This file could not be read", ex);
        }
    }

    void writeFile(String path, byte[] bytes) throws LocalRepositoryException {
        File file = resolve(path);
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new LocalRepositoryException("DOCUMENT_WRITE_FAILED", "The folder could not be created");
        }
        // Write a sibling then rename, so a crash never leaves half a file.
        File temporary = new File(parent, "." + file.getName() + ".marktext-tmp");
        try (OutputStream output = new FileOutputStream(temporary)) {
            output.write(bytes);
            output.flush();
        } catch (IOException ex) {
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
            throw new LocalRepositoryException("DOCUMENT_WRITE_FAILED", "This file could not be saved", ex);
        }
        if (!temporary.renameTo(file)) {
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
            throw new LocalRepositoryException("DOCUMENT_WRITE_FAILED", "This file could not be saved");
        }
    }

    /** The file as of the last commit (HEAD); null when it is new or HEAD is unborn. */
    byte[] readCommittedFile(String path) throws LocalRepositoryException {
        resolve(path);
        Repository repository = git.getRepository();
        try (RevWalk walk = new RevWalk(repository)) {
            ObjectId head = repository.resolve(Constants.HEAD);
            if (head == null) {
                return null;
            }
            RevCommit commit = walk.parseCommit(head);
            try (TreeWalk treeWalk = TreeWalk.forPath(repository, path, commit.getTree())) {
                if (treeWalk == null) {
                    return null;
                }
                ObjectLoader loader = repository.open(treeWalk.getObjectId(0));
                return loader.getSize() > MAX_DIFF_BYTES ? null : loader.getBytes();
            }
        } catch (IOException ex) {
            throw new LocalRepositoryException("STATUS_FAILED", "The committed version could not be read", ex);
        }
    }

    /** Stages everything (new, changed and deleted files) and commits it. */
    CommitInfo commitAll(String message) throws LocalRepositoryException {
        try {
            // `add .` would stage files still full of conflict markers as
            // resolved; conflicts are only resolved through resolveConflict.
            if (!git.status().call().getConflicting().isEmpty()) {
                throw new LocalRepositoryException("CONFLICTS_UNRESOLVED", "Resolve every conflict before committing");
            }
            git.add().addFilepattern(".").call();
            git.add().addFilepattern(".").setUpdate(true).call();
            RevCommit commit = git.commit().setMessage(message).setAllowEmpty(isMergeInProgress()).call();
            return describe(commit);
        } catch (EmptyCommitException ex) {
            throw new LocalRepositoryException("NOTHING_TO_COMMIT", "There are no changes to commit", ex);
        } catch (UnmergedPathsException ex) {
            throw new LocalRepositoryException("CONFLICTS_UNRESOLVED", "Resolve every conflict before committing", ex);
        } catch (GitAPIException ex) {
            throw failure("COMMIT_FAILED", "The commit could not be created", ex);
        }
    }

    /** Pushes the current branch to origin, setting it as upstream when new. */
    void push(CredentialsProvider credentials, ProgressListener progress) throws LocalRepositoryException {
        Repository repository = git.getRepository();
        String branch;
        try {
            branch = repository.getBranch();
            if (repository.resolve(Constants.HEAD) == null) {
                throw new LocalRepositoryException("NOTHING_TO_PUSH", "Commit something before pushing");
            }
        } catch (IOException ex) {
            throw new LocalRepositoryException("PUSH_FAILED", "The branch could not be read", ex);
        }
        try {
            Iterable<PushResult> results = git.push()
                .setRemote(REMOTE)
                .setRefSpecs(new RefSpec(Constants.R_HEADS + branch + ":" + Constants.R_HEADS + branch))
                .setCredentialsProvider(credentials)
                .setProgressMonitor(monitor(progress))
                .call();
            for (PushResult result : results) {
                for (RemoteRefUpdate update : result.getRemoteUpdates()) {
                    switch (update.getStatus()) {
                        case OK:
                        case UP_TO_DATE:
                            break;
                        case REJECTED_NONFASTFORWARD:
                        case REJECTED_REMOTE_CHANGED:
                            throw new LocalRepositoryException(
                                "PUSH_REJECTED",
                                "The remote branch has new commits; pull them first"
                            );
                        default:
                            String detail = update.getMessage();
                            throw new LocalRepositoryException(
                                "PUSH_REJECTED_OTHER",
                                "The push was rejected" + (detail == null || detail.length() == 0 ? "" : ": " + detail)
                            );
                    }
                }
            }
        } catch (GitAPIException ex) {
            throw translate(ex, "PUSH_FAILED", "The push failed");
        }
        ensureUpstream(branch);
    }

    /**
     * Fetches and merges the upstream branch. Conflicts leave the
     * repository MERGING with markers in the files; edits to files the
     * merge would overwrite stop it before anything changes.
     */
    PullOutcome pull(CredentialsProvider credentials, ProgressListener progress) throws LocalRepositoryException {
        if (isMergeInProgress()) {
            throw new LocalRepositoryException("MERGE_IN_PROGRESS", "Finish or abort the current merge first");
        }
        try {
            String branch = git.getRepository().getBranch();
            if (git.getRepository().getConfig().getString("branch", branch, "merge") == null) {
                throw new LocalRepositoryException("NO_UPSTREAM", "This branch is not on the remote yet; push it first");
            }
        } catch (IOException ex) {
            throw new LocalRepositoryException("PULL_FAILED", "The branch could not be read", ex);
        }
        try {
            PullResult result = git.pull()
                .setRemote(REMOTE)
                .setCredentialsProvider(credentials)
                .setProgressMonitor(monitor(progress))
                .call();
            MergeResult merge = result.getMergeResult();
            if (merge == null) {
                return new PullOutcome("up-to-date", Collections.emptyList());
            }
            switch (merge.getMergeStatus()) {
                case ALREADY_UP_TO_DATE:
                    return new PullOutcome("up-to-date", Collections.emptyList());
                case FAST_FORWARD:
                case FAST_FORWARD_SQUASHED:
                    return new PullOutcome("fast-forward", Collections.emptyList());
                case MERGED:
                case MERGED_SQUASHED:
                case MERGED_NOT_COMMITTED:
                    return new PullOutcome("merged", Collections.emptyList());
                case CONFLICTING:
                    List<String> conflicts = new ArrayList<>(merge.getConflicts().keySet());
                    Collections.sort(conflicts);
                    return new PullOutcome("conflicts", conflicts);
                case CHECKOUT_CONFLICT:
                    throw new LocalRepositoryException(
                        "LOCAL_CHANGES_BLOCK_PULL",
                        "Commit or revert your edits to these files before pulling",
                        sorted(merge.getCheckoutConflicts()),
                        null
                    );
                case FAILED:
                    throw new LocalRepositoryException(
                        "LOCAL_CHANGES_BLOCK_PULL",
                        "Commit or revert your edits to these files before pulling",
                        merge.getFailingPaths() == null
                            ? Collections.emptyList()
                            : sorted(new ArrayList<>(merge.getFailingPaths().keySet())),
                        null
                    );
                default:
                    throw new LocalRepositoryException("PULL_FAILED", "The pull could not be completed");
            }
        } catch (InvalidConfigurationException ex) {
            throw new LocalRepositoryException("NO_UPSTREAM", "This branch is not on the remote yet; push it first", ex);
        } catch (CheckoutConflictException ex) {
            // A fast-forward that would overwrite uncommitted edits.
            throw new LocalRepositoryException(
                "LOCAL_CHANGES_BLOCK_PULL",
                "Commit or revert your edits to these files before pulling",
                sorted(ex.getConflictingPaths()),
                ex
            );
        } catch (GitAPIException ex) {
            throw translate(ex, "PULL_FAILED", "The pull failed");
        }
    }

    /**
     * Resolves one conflicted file: "ours" (this device's version),
     * "theirs" (the incoming version) or "edited" (the file as it is now in
     * the working tree, after editing out the markers).
     */
    void resolveConflict(String path, String choice) throws LocalRepositoryException {
        File file = resolve(path);
        try {
            if ("edited".equals(choice)) {
                if (file.exists()) {
                    git.add().addFilepattern(path).call();
                } else {
                    git.rm().addFilepattern(path).call();
                }
                return;
            }
            int stage;
            CheckoutCommand.Stage checkoutStage;
            if ("ours".equals(choice)) {
                stage = DirCacheEntry.STAGE_2;
                checkoutStage = CheckoutCommand.Stage.OURS;
            } else if ("theirs".equals(choice)) {
                stage = DirCacheEntry.STAGE_3;
                checkoutStage = CheckoutCommand.Stage.THEIRS;
            } else {
                throw new LocalRepositoryException("INVALID_CHOICE", "Unknown conflict resolution");
            }
            if (!hasStage(path, stage)) {
                // The chosen side deleted the file.
                git.rm().addFilepattern(path).call();
                return;
            }
            git.checkout().setStage(checkoutStage).addPath(path).call();
            git.add().addFilepattern(path).call();
        } catch (GitAPIException | IOException ex) {
            throw failure("RESOLVE_FAILED", "The conflict could not be resolved", ex);
        }
    }

    /** Commits the resolved merge or revert. */
    CommitInfo completeMerge(String message) throws LocalRepositoryException {
        if (!isMergeInProgress()) {
            throw new LocalRepositoryException("NO_MERGE_IN_PROGRESS", "There is no merge to complete");
        }
        String finalMessage = message;
        if (finalMessage == null || finalMessage.trim().length() == 0) {
            try {
                finalMessage = git.getRepository().readMergeCommitMsg();
            } catch (IOException ex) {
                finalMessage = null;
            }
        }
        return commitAll(finalMessage == null || finalMessage.trim().length() == 0 ? "Merge" : finalMessage);
    }

    /** Drops the merge or revert in progress and everything it changed. */
    void abortMerge() throws LocalRepositoryException {
        if (!isMergeInProgress()) {
            throw new LocalRepositoryException("NO_MERGE_IN_PROGRESS", "There is no merge to abort");
        }
        try {
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(Constants.HEAD).call();
        } catch (GitAPIException ex) {
            throw failure("ABORT_FAILED", "The merge could not be aborted", ex);
        }
    }

    /**
     * Throws away uncommitted edits: the given files return to their last
     * committed version (new files are deleted). An empty list discards
     * every change in the working tree.
     */
    void discardChanges(List<String> paths) throws LocalRepositoryException {
        if (isMergeInProgress()) {
            throw new LocalRepositoryException("MERGE_IN_PROGRESS", "Finish or abort the current merge first");
        }
        try {
            if (paths.isEmpty()) {
                if (git.getRepository().resolve(Constants.HEAD) != null) {
                    git.reset().setMode(ResetCommand.ResetType.HARD).setRef(Constants.HEAD).call();
                }
                git.clean().setCleanDirectories(true).call();
                return;
            }
            for (String path : paths) {
                File file = resolve(path);
                if (!isTrackedAtHead(path)) {
                    git.reset().addPath(path).call();
                    deleteRecursively(file);
                } else {
                    git.reset().addPath(path).call();
                    git.checkout().addPath(path).call();
                }
            }
        } catch (GitAPIException | IOException ex) {
            throw failure("DISCARD_FAILED", "The changes could not be discarded", ex);
        }
    }

    List<BranchInfo> branches() throws LocalRepositoryException {
        try {
            String current = git.getRepository().getBranch();
            Map<String, boolean[]> byName = new LinkedHashMap<>();
            for (Ref ref : git.branchList().setListMode(ListBranchCommand.ListMode.ALL).call()) {
                String name = ref.getName();
                boolean local = name.startsWith(Constants.R_HEADS);
                String remotePrefix = Constants.R_REMOTES + REMOTE + "/";
                if (!local && !name.startsWith(remotePrefix)) {
                    continue;
                }
                String shortName = local ? name.substring(Constants.R_HEADS.length()) : name.substring(remotePrefix.length());
                if ("HEAD".equals(shortName)) {
                    continue;
                }
                boolean[] flags = byName.computeIfAbsent(shortName, key -> new boolean[2]);
                flags[local ? 0 : 1] = true;
            }
            List<String> names = new ArrayList<>(byName.keySet());
            Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
            List<BranchInfo> branches = new ArrayList<>();
            for (String name : names) {
                boolean[] flags = byName.get(name);
                BranchInfo info = new BranchInfo(name, name.equals(current), flags[0], flags[1]);
                if (info.current) {
                    branches.add(0, info);
                } else {
                    branches.add(info);
                }
            }
            return branches;
        } catch (GitAPIException | IOException ex) {
            throw failure("BRANCHES_FAILED", "The branches could not be listed", ex);
        }
    }

    /**
     * Switches branches; a remote-only branch becomes a local one tracking
     * it. Uncommitted edits are carried over unless the switch would
     * overwrite them, which is refused with the files named.
     */
    void checkoutBranch(String name) throws LocalRepositoryException {
        requireBranchName(name);
        if (isMergeInProgress()) {
            throw new LocalRepositoryException("MERGE_IN_PROGRESS", "Finish or abort the current merge first");
        }
        try {
            boolean local = git.getRepository().findRef(Constants.R_HEADS + name) != null;
            CheckoutCommand checkout = git.checkout().setName(name);
            if (!local) {
                if (git.getRepository().findRef(Constants.R_REMOTES + REMOTE + "/" + name) == null) {
                    throw new LocalRepositoryException("BRANCH_NOT_FOUND", "This branch does not exist");
                }
                checkout.setCreateBranch(true)
                    .setStartPoint(REMOTE + "/" + name)
                    .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK);
            }
            checkout.call();
        } catch (CheckoutConflictException ex) {
            throw new LocalRepositoryException(
                "LOCAL_CHANGES_BLOCK_CHECKOUT",
                "Commit or revert your edits to these files before switching",
                sorted(ex.getConflictingPaths()),
                ex
            );
        } catch (GitAPIException | IOException ex) {
            throw failure("CHECKOUT_FAILED", "The branch could not be switched", ex);
        }
    }

    /** Creates a branch at the current commit and switches to it (pushed later). */
    void createBranch(String name) throws LocalRepositoryException {
        requireBranchName(name);
        try {
            git.checkout().setCreateBranch(true).setName(name).call();
        } catch (RefAlreadyExistsException ex) {
            throw new LocalRepositoryException("BRANCH_EXISTS", "A branch with this name already exists", ex);
        } catch (GitAPIException ex) {
            throw failure("CHECKOUT_FAILED", "The branch could not be created", ex);
        }
    }

    List<CommitInfo> log(int maxCount, int skip) throws LocalRepositoryException {
        try {
            if (git.getRepository().resolve(Constants.HEAD) == null) {
                return Collections.emptyList();
            }
            List<CommitInfo> commits = new ArrayList<>();
            for (RevCommit commit : git.log().setMaxCount(maxCount).setSkip(skip).call()) {
                commits.add(describe(commit));
            }
            return commits;
        } catch (NoHeadException ex) {
            return Collections.emptyList();
        } catch (GitAPIException | IOException ex) {
            throw failure("LOG_FAILED", "The history could not be read", ex);
        }
    }

    /** What one commit changed against its first parent (the empty tree for the root). */
    List<FileDiff> commitDiff(String sha) throws LocalRepositoryException {
        Repository repository = git.getRepository();
        try (RevWalk walk = new RevWalk(repository);
             DiffFormatter formatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            ObjectId id = repository.resolve(sha);
            if (id == null) {
                throw new LocalRepositoryException("COMMIT_NOT_FOUND", "This commit does not exist");
            }
            RevCommit commit = walk.parseCommit(id);
            formatter.setRepository(repository);
            formatter.setDetectRenames(true);
            AbstractTreeIterator oldTree = commit.getParentCount() == 0
                ? new EmptyTreeIterator()
                : treeIterator(repository, walk.parseCommit(commit.getParent(0)));
            List<DiffEntry> entries = formatter.scan(oldTree, treeIterator(repository, commit));
            List<FileDiff> diffs = new ArrayList<>();
            for (DiffEntry entry : entries) {
                byte[] oldBytes = loadBlob(repository, entry.getOldId().toObjectId());
                byte[] newBytes = loadBlob(repository, entry.getNewId().toObjectId());
                boolean tooLarge = oldBytes == OVERSIZED || newBytes == OVERSIZED;
                boolean binary = !tooLarge && (isBinary(oldBytes) || isBinary(newBytes));
                diffs.add(new FileDiff(
                    entry.getChangeType().name().toLowerCase(Locale.US),
                    entry.getChangeType() == DiffEntry.ChangeType.ADD ? null : entry.getOldPath(),
                    entry.getChangeType() == DiffEntry.ChangeType.DELETE ? null : entry.getNewPath(),
                    tooLarge || binary ? null : oldBytes,
                    tooLarge || binary ? null : newBytes,
                    binary,
                    tooLarge
                ));
            }
            return diffs;
        } catch (org.eclipse.jgit.errors.MissingObjectException | org.eclipse.jgit.errors.IncorrectObjectTypeException ex) {
            throw new LocalRepositoryException("COMMIT_NOT_FOUND", "This commit does not exist", ex);
        } catch (IOException ex) {
            throw new LocalRepositoryException("LOG_FAILED", "The commit could not be read", ex);
        }
    }

    /**
     * Creates a commit undoing the given one. Conflicts leave the
     * repository REVERTING, resolved like a merge.
     */
    PullOutcome revertCommit(String sha) throws LocalRepositoryException {
        if (isMergeInProgress()) {
            throw new LocalRepositoryException("MERGE_IN_PROGRESS", "Finish or abort the current merge first");
        }
        try {
            ObjectId id = git.getRepository().resolve(sha);
            if (id == null) {
                throw new LocalRepositoryException("COMMIT_NOT_FOUND", "This commit does not exist");
            }
            org.eclipse.jgit.api.RevertCommand revert = git.revert().include(id);
            RevCommit reverted = revert.call();
            if (reverted != null) {
                return new PullOutcome("merged", Collections.emptyList());
            }
            if (revert.getUnmergedPaths() != null && !revert.getUnmergedPaths().isEmpty()) {
                return new PullOutcome("conflicts", sorted(revert.getUnmergedPaths()));
            }
            if (revert.getFailingResult() != null && revert.getFailingResult().getFailingPaths() != null) {
                throw new LocalRepositoryException(
                    "LOCAL_CHANGES_BLOCK_REVERT",
                    "Commit or revert your edits to these files first",
                    sorted(new ArrayList<>(revert.getFailingResult().getFailingPaths().keySet())),
                    null
                );
            }
            throw new LocalRepositoryException("REVERT_FAILED", "The commit could not be reverted");
        } catch (GitAPIException | IOException ex) {
            throw failure("REVERT_FAILED", "The commit could not be reverted", ex);
        }
    }

    @Override
    public void close() {
        git.close();
    }

    // --- helpers -------------------------------------------------------

    /** Resolves a repository-relative path, refusing escapes and .git. */
    File resolve(String path) throws LocalRepositoryException {
        if (path == null || path.length() == 0 || path.startsWith("/") || path.contains("\\")) {
            throw invalidPath();
        }
        for (String segment : path.split("/", -1)) {
            if (segment.length() == 0 || segment.equals(".") || segment.equals("..")
                || segment.equalsIgnoreCase(Constants.DOT_GIT)) {
                throw invalidPath();
            }
        }
        File file = new File(workTree, path);
        try {
            String root = workTree.getCanonicalPath() + File.separator;
            if (!file.getCanonicalPath().startsWith(root)) {
                throw invalidPath();
            }
        } catch (IOException ex) {
            throw invalidPath();
        }
        return file;
    }

    boolean isMergeInProgress() {
        RepositoryState state = git.getRepository().getRepositoryState();
        return state == RepositoryState.MERGING
            || state == RepositoryState.MERGING_RESOLVED
            || state == RepositoryState.REVERTING
            || state == RepositoryState.REVERTING_RESOLVED;
    }

    private boolean isTrackedAtHead(String path) throws IOException {
        Repository repository = git.getRepository();
        ObjectId head = repository.resolve(Constants.HEAD);
        if (head == null) {
            return false;
        }
        try (RevWalk walk = new RevWalk(repository);
             TreeWalk treeWalk = TreeWalk.forPath(repository, path, walk.parseCommit(head).getTree())) {
            return treeWalk != null;
        }
    }

    private boolean hasStage(String path, int stage) throws IOException {
        DirCache cache = git.getRepository().readDirCache();
        for (int index = cache.findEntry(path); index >= 0 && index < cache.getEntryCount(); index++) {
            DirCacheEntry entry = cache.getEntry(index);
            if (!entry.getPathString().equals(path)) {
                break;
            }
            if (entry.getStage() == stage) {
                return true;
            }
        }
        return false;
    }

    private void ensureUpstream(String branch) throws LocalRepositoryException {
        StoredConfig config = git.getRepository().getConfig();
        if (config.getString("branch", branch, "remote") != null) {
            return;
        }
        config.setString("branch", branch, "remote", REMOTE);
        config.setString("branch", branch, "merge", Constants.R_HEADS + branch);
        try {
            config.save();
        } catch (IOException ex) {
            throw new LocalRepositoryException("REPOSITORY_WRITE_FAILED", "The repository settings could not be saved", ex);
        }
    }

    private static final byte[] OVERSIZED = new byte[0];

    private static byte[] loadBlob(Repository repository, ObjectId id) throws IOException {
        if (id == null || id.equals(ObjectId.zeroId())) {
            return null;
        }
        ObjectLoader loader = repository.open(id);
        return loader.getSize() > MAX_DIFF_BYTES ? OVERSIZED : loader.getBytes();
    }

    private static boolean isBinary(byte[] bytes) {
        return bytes != null && RawText.isBinary(bytes);
    }

    private static AbstractTreeIterator treeIterator(Repository repository, RevCommit commit) throws IOException {
        CanonicalTreeParser parser = new CanonicalTreeParser();
        try (org.eclipse.jgit.lib.ObjectReader reader = repository.newObjectReader()) {
            parser.reset(reader, commit.getTree());
        }
        return parser;
    }

    private static CommitInfo describe(RevCommit commit) {
        PersonIdent author = commit.getAuthorIdent();
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        return new CommitInfo(
            commit.getName(),
            commit.abbreviate(7).name(),
            commit.getShortMessage(),
            commit.getFullMessage(),
            author == null ? "" : author.getName(),
            author == null ? "" : iso.format(new Date(author.getWhen().getTime())),
            commit.getParentCount()
        );
    }

    private static String stateName(RepositoryState state) {
        switch (state) {
            case MERGING:
                return "MERGING";
            case MERGING_RESOLVED:
                return "MERGING_RESOLVED";
            case REVERTING:
                return "REVERTING";
            case REVERTING_RESOLVED:
                return "REVERTING_RESOLVED";
            case SAFE:
                return "SAFE";
            default:
                return state.name();
        }
    }

    private static void requireBranchName(String name) throws LocalRepositoryException {
        if (!GitHubClient.isValidBranchName(name)) {
            throw new LocalRepositoryException("INVALID_BRANCH_NAME", "This branch name is not valid");
        }
    }

    private static LocalRepositoryException invalidPath() {
        return new LocalRepositoryException("INVALID_PATH", "This path is not inside the repository");
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        Set<String> all = new LinkedHashSet<>(first);
        all.addAll(second);
        return all;
    }

    private static List<String> sorted(List<String> values) {
        List<String> copy = new ArrayList<>(values == null ? Collections.emptyList() : values);
        Collections.sort(copy);
        return copy;
    }

    private static LocalRepositoryException failure(String code, String message, Exception cause) {
        return new LocalRepositoryException(code, message, cause);
    }

    /** Network and authentication failures get their own codes; the rest the fallback. */
    static LocalRepositoryException translate(GitAPIException ex, String fallbackCode, String fallbackMessage) {
        if (ex instanceof org.eclipse.jgit.api.errors.InvalidRemoteException) {
            return new LocalRepositoryException("REMOTE_NOT_FOUND", "The remote repository was not found", ex);
        }
        if (ex instanceof TransportException) {
            String detail = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(Locale.US);
            if (detail.contains("not authorized") || detail.contains("authentication")
                || detail.contains("401") || detail.contains("403")) {
                return new LocalRepositoryException(
                    "AUTH_FAILED",
                    "The remote refused the sign-in; connect the GitHub account again",
                    ex
                );
            }
            if (detail.contains("not found") || detail.contains("404")) {
                return new LocalRepositoryException("REMOTE_NOT_FOUND", "The remote repository was not found", ex);
            }
            for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause()) {
                if (cause instanceof UnknownHostException || cause instanceof java.net.SocketException
                    || cause instanceof java.net.SocketTimeoutException) {
                    return new LocalRepositoryException("NETWORK_FAILED", "The remote could not be reached", ex);
                }
            }
            return new LocalRepositoryException("NETWORK_FAILED", "The remote could not be reached", ex);
        }
        return new LocalRepositoryException(fallbackCode, fallbackMessage, ex);
    }

    static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static ProgressMonitor monitor(ProgressListener listener) {
        if (listener == null) {
            return null;
        }
        return new ProgressMonitor() {
            private String task = "";
            private int total = 0;
            private int completed = 0;

            @Override
            public void start(int totalTasks) {
            }

            @Override
            public void beginTask(String title, int totalWork) {
                task = title == null ? "" : title;
                total = totalWork == ProgressMonitor.UNKNOWN ? 0 : totalWork;
                completed = 0;
                listener.onProgress(task, completed, total);
            }

            @Override
            public void update(int work) {
                completed += work;
                listener.onProgress(task, completed, total);
            }

            @Override
            public void endTask() {
                listener.onProgress(task, total, total);
            }

            @Override
            public boolean isCancelled() {
                return false;
            }
        };
    }
}

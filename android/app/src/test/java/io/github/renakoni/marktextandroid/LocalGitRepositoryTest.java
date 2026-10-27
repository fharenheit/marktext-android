package io.github.renakoni.marktextandroid;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Real Git round trips against a local bare "remote": two device clones
 * stand in for this phone and another collaborator.
 */
public class LocalGitRepositoryTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @BeforeClass
    public static void sandboxGit() throws Exception {
        // Keep the host's ~/.gitconfig out of the tests, as on the device.
        SandboxedGitSystemReader.install(Files.createTempDirectory("marktext-git-config").toFile());
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void assertFailsWith(String code, ThrowingRunnable runnable) throws Exception {
        try {
            runnable.run();
            fail("Expected " + code);
        } catch (LocalRepositoryException ex) {
            assertEquals(code, ex.code);
        }
    }

    private static String text(byte[] bytes) {
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /** A bare remote seeded with README.md and docs/guide.md on main. */
    private String createRemote() throws Exception {
        File bare = temporary.newFolder("remote.git");
        Git.init().setBare(true).setInitialBranch("main").setDirectory(bare).call().close();
        File seed = temporary.newFolder("seed");
        try (Git git = Git.cloneRepository().setURI(bare.toURI().toString()).setDirectory(seed).call()) {
            Files.write(new File(seed, "README.md").toPath(), bytes("# Hello\n\nfirst line\n"));
            new File(seed, "docs").mkdirs();
            Files.write(new File(seed, "docs/guide.md").toPath(), bytes("# Guide\n"));
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Initial").setAuthor("Seed", "seed@example.com")
                .setCommitter("Seed", "seed@example.com").call();
            git.push().setRefSpecs(new org.eclipse.jgit.transport.RefSpec("HEAD:refs/heads/main")).call();
        }
        return bare.toURI().toString();
    }

    private LocalGitRepository cloneDevice(String remote, String name) throws Exception {
        LocalGitRepository repository =
            LocalGitRepository.cloneInto(temporary.newFolder(name), remote, null, null, null);
        repository.setIdentity(name, name + "@example.com");
        return repository;
    }

    @Test
    public void clonesListsReadsAndWritesTheWorkingTree() throws Exception {
        List<String> progressTasks = new ArrayList<>();
        try (LocalGitRepository repository = LocalGitRepository.cloneInto(
            temporary.newFolder("phone"), createRemote(), null, null,
            (task, completed, total) -> progressTasks.add(task))) {

            List<LocalGitRepository.Entry> root = repository.listFolder("");
            assertEquals("docs", root.get(0).name);
            assertTrue(root.get(0).isFolder);
            assertEquals("README.md", root.get(1).name);
            assertEquals("docs/guide.md", repository.listFolder("docs").get(0).path);
            assertEquals("# Guide\n", text(repository.readFile("docs/guide.md", 1024)));
            assertFalse(progressTasks.isEmpty());

            LocalGitRepository.StatusSummary clean = repository.status();
            assertEquals("main", clean.branch);
            assertEquals("SAFE", clean.state);
            assertTrue(clean.hasUpstream);
            assertTrue(clean.changes.isEmpty());

            repository.writeFile("README.md", bytes("# Hello\n\nchanged\n"));
            repository.writeFile("notes/new.md", bytes("new"));
            List<LocalGitRepository.FileChange> changes = repository.status().changes;
            assertEquals(2, changes.size());
            assertEquals("README.md", changes.get(1 - indexOf(changes, "notes/new.md")).path);
            assertEquals("modified", changes.get(1 - indexOf(changes, "notes/new.md")).kind);
            assertEquals("added", changes.get(indexOf(changes, "notes/new.md")).kind);
            assertEquals("# Hello\n\nfirst line\n", text(repository.readCommittedFile("README.md")));
            assertNull(repository.readCommittedFile("notes/new.md"));
        }
    }

    private static int indexOf(List<LocalGitRepository.FileChange> changes, String path) {
        for (int index = 0; index < changes.size(); index++) {
            if (changes.get(index).path.equals(path)) {
                return index;
            }
        }
        return -1;
    }

    @Test
    public void refusesPathsOutsideTheWorkingTree() throws Exception {
        try (LocalGitRepository repository = cloneDevice(createRemote(), "phone")) {
            for (String path : new String[] {"../escape.md", "/etc/passwd", ".git/config", "a//b.md", "docs/../x", ""}) {
                assertFailsWith("INVALID_PATH", () -> repository.readFile(path, 1024));
            }
            assertFailsWith("INVALID_PATH", () -> repository.writeFile(".git/hooks/pre-commit", bytes("x")));
        }
    }

    @Test
    public void commitsPushesAndAnotherClonePullsFastForward() throws Exception {
        String remote = createRemote();
        try (LocalGitRepository phone = cloneDevice(remote, "phone");
             LocalGitRepository laptop = cloneDevice(remote, "laptop")) {
            phone.writeFile("README.md", bytes("# Hello\n\nfrom phone\n"));
            phone.writeFile("docs/guide.md", bytes("# Guide v2\n"));

            LocalGitRepository.CommitInfo commit = phone.commitAll("Edit on phone");
            assertEquals("Edit on phone", commit.summary);
            assertEquals("phone", commit.author);
            assertEquals(1, phone.status().ahead);
            assertTrue(phone.status().changes.isEmpty());
            assertFailsWith("NOTHING_TO_COMMIT", () -> phone.commitAll("Again"));

            phone.push(null, null);
            assertEquals(0, phone.status().ahead);

            LocalGitRepository.PullOutcome pulled = laptop.pull(null, null);
            assertEquals("fast-forward", pulled.result);
            assertEquals("# Guide v2\n", text(laptop.readFile("docs/guide.md", 1024)));
            assertEquals("up-to-date", laptop.pull(null, null).result);
        }
    }

    @Test
    public void pushIsRejectedWhenTheRemoteMovedOn() throws Exception {
        String remote = createRemote();
        try (LocalGitRepository phone = cloneDevice(remote, "phone");
             LocalGitRepository laptop = cloneDevice(remote, "laptop")) {
            laptop.writeFile("docs/guide.md", bytes("laptop"));
            laptop.commitAll("Laptop edit");
            laptop.push(null, null);

            phone.writeFile("README.md", bytes("phone"));
            phone.commitAll("Phone edit");
            assertFailsWith("PUSH_REJECTED", () -> phone.push(null, null));

            // Different files: the pull merges cleanly and the push goes through.
            assertEquals("merged", phone.pull(null, null).result);
            phone.push(null, null);
            assertEquals(0, phone.status().ahead);
        }
    }

    @Test
    public void conflictingPullsAreResolvedPerFileAndCompleted() throws Exception {
        String remote = createRemote();
        try (LocalGitRepository phone = cloneDevice(remote, "phone");
             LocalGitRepository laptop = cloneDevice(remote, "laptop")) {
            laptop.writeFile("README.md", bytes("# Hello\n\nfrom laptop\n"));
            laptop.writeFile("docs/guide.md", bytes("# Guide laptop\n"));
            laptop.commitAll("Laptop edits");
            laptop.push(null, null);

            phone.writeFile("README.md", bytes("# Hello\n\nfrom phone\n"));
            phone.writeFile("docs/guide.md", bytes("# Guide phone\n"));
            phone.commitAll("Phone edits");

            LocalGitRepository.PullOutcome outcome = phone.pull(null, null);
            assertEquals("conflicts", outcome.result);
            assertEquals(java.util.Arrays.asList("README.md", "docs/guide.md"), outcome.conflicts);
            LocalGitRepository.StatusSummary merging = phone.status();
            assertEquals("MERGING", merging.state);
            assertEquals("conflicting", merging.changes.get(0).kind);
            assertTrue(text(phone.readFile("README.md", 4096)).contains("<<<<<<<"));
            assertFailsWith("CONFLICTS_UNRESOLVED", () -> phone.completeMerge(null));
            assertFailsWith("MERGE_IN_PROGRESS", () -> phone.pull(null, null));

            phone.resolveConflict("README.md", "theirs");
            assertEquals("# Hello\n\nfrom laptop\n", text(phone.readFile("README.md", 4096)));
            phone.writeFile("docs/guide.md", bytes("# Guide merged by hand\n"));
            phone.resolveConflict("docs/guide.md", "edited");

            LocalGitRepository.CommitInfo merge = phone.completeMerge(null);
            assertEquals(2, merge.parentCount);
            assertEquals("SAFE", phone.status().state);
            phone.push(null, null);

            assertEquals("fast-forward", laptop.pull(null, null).result);
            assertEquals("# Guide merged by hand\n", text(laptop.readFile("docs/guide.md", 4096)));
        }
    }

    @Test
    public void oursKeepsTheDeviceVersionAndAbortRestoresEverything() throws Exception {
        String remote = createRemote();
        try (LocalGitRepository phone = cloneDevice(remote, "phone");
             LocalGitRepository laptop = cloneDevice(remote, "laptop")) {
            laptop.writeFile("README.md", bytes("laptop"));
            laptop.commitAll("Laptop");
            laptop.push(null, null);
            phone.writeFile("README.md", bytes("phone"));
            phone.commitAll("Phone");

            assertEquals("conflicts", phone.pull(null, null).result);
            phone.resolveConflict("README.md", "ours");
            assertEquals("phone", text(phone.readFile("README.md", 64)));

            phone.abortMerge();
            assertEquals("SAFE", phone.status().state);
            assertEquals("phone", text(phone.readFile("README.md", 64)));
            assertTrue(phone.status().changes.isEmpty());
            assertFailsWith("NO_MERGE_IN_PROGRESS", phone::abortMerge);
        }
    }

    @Test
    public void localEditsBlockAPullThatWouldOverwriteThem() throws Exception {
        String remote = createRemote();
        try (LocalGitRepository phone = cloneDevice(remote, "phone");
             LocalGitRepository laptop = cloneDevice(remote, "laptop")) {
            laptop.writeFile("README.md", bytes("laptop"));
            laptop.commitAll("Laptop");
            laptop.push(null, null);
            phone.writeFile("README.md", bytes("uncommitted phone edit"));

            try {
                phone.pull(null, null);
                fail("Expected LOCAL_CHANGES_BLOCK_PULL");
            } catch (LocalRepositoryException ex) {
                assertEquals("LOCAL_CHANGES_BLOCK_PULL", ex.code);
                assertEquals(Collections.singletonList("README.md"), ex.paths);
            }
            assertEquals("uncommitted phone edit", text(phone.readFile("README.md", 64)));
        }
    }

    @Test
    public void discardRevertsSelectedFilesOrEverything() throws Exception {
        try (LocalGitRepository phone = cloneDevice(createRemote(), "phone")) {
            phone.writeFile("README.md", bytes("edited"));
            phone.writeFile("docs/guide.md", bytes("edited too"));
            phone.writeFile("draft.md", bytes("new file"));

            phone.discardChanges(Collections.singletonList("README.md"));
            assertEquals("# Hello\n\nfirst line\n", text(phone.readFile("README.md", 64)));
            assertEquals("edited too", text(phone.readFile("docs/guide.md", 64)));

            phone.discardChanges(Collections.singletonList("draft.md"));
            assertFalse(new File(phone.getWorkTree(), "draft.md").exists());

            new File(phone.getWorkTree(), "README.md").delete();
            phone.writeFile("other.md", bytes("x"));
            phone.discardChanges(Collections.emptyList());
            assertTrue(phone.status().changes.isEmpty());
            assertEquals("# Guide\n", text(phone.readFile("docs/guide.md", 64)));
            assertTrue(new File(phone.getWorkTree(), "README.md").exists());
            assertFalse(new File(phone.getWorkTree(), "other.md").exists());
        }
    }

    @Test
    public void branchesAreCreatedSwitchedPushedAndTracked() throws Exception {
        String remote = createRemote();
        try (LocalGitRepository phone = cloneDevice(remote, "phone");
             LocalGitRepository laptop = cloneDevice(remote, "laptop")) {
            phone.createBranch("feature/notes");
            assertEquals("feature/notes", phone.status().branch);
            assertFalse(phone.status().hasUpstream);
            assertFailsWith("NO_UPSTREAM", () -> phone.pull(null, null));
            phone.writeFile("notes.md", bytes("notes"));
            phone.commitAll("Notes");
            phone.push(null, null);
            assertTrue(phone.status().hasUpstream);
            assertFailsWith("BRANCH_EXISTS", () -> phone.createBranch("main"));
            assertFailsWith("INVALID_BRANCH_NAME", () -> phone.createBranch("bad..name"));

            phone.checkoutBranch("main");
            assertFalse(new File(phone.getWorkTree(), "notes.md").exists());

            laptop.pull(null, null);
            List<LocalGitRepository.BranchInfo> branches = laptop.branches();
            assertEquals("main", branches.get(0).name);
            assertTrue(branches.get(0).current);
            LocalGitRepository.BranchInfo feature = branches.get(1);
            assertEquals("feature/notes", feature.name);
            assertFalse(feature.local);
            assertTrue(feature.remote);

            laptop.checkoutBranch("feature/notes");
            assertEquals("notes", text(laptop.readFile("notes.md", 64)));
            assertTrue(laptop.status().hasUpstream);
            assertFailsWith("BRANCH_NOT_FOUND", () -> laptop.checkoutBranch("missing"));
        }
    }

    @Test
    public void switchingRefusesToOverwriteUncommittedEdits() throws Exception {
        String remote = createRemote();
        try (LocalGitRepository phone = cloneDevice(remote, "phone")) {
            phone.createBranch("other");
            phone.writeFile("README.md", bytes("on other"));
            phone.commitAll("Other");
            phone.checkoutBranch("main");
            phone.writeFile("README.md", bytes("uncommitted"));

            try {
                phone.checkoutBranch("other");
                fail("Expected LOCAL_CHANGES_BLOCK_CHECKOUT");
            } catch (LocalRepositoryException ex) {
                assertEquals("LOCAL_CHANGES_BLOCK_CHECKOUT", ex.code);
                assertEquals(Collections.singletonList("README.md"), ex.paths);
            }
            assertEquals("main", phone.status().branch);
        }
    }

    @Test
    public void historyAndCommitDiffsDescribeEachCommit() throws Exception {
        try (LocalGitRepository phone = cloneDevice(createRemote(), "phone")) {
            phone.writeFile("README.md", bytes("# Hello\n\nsecond\n"));
            phone.writeFile("new.md", bytes("new\n"));
            new File(phone.getWorkTree(), "docs/guide.md").delete();
            phone.commitAll("Second\n\nWith a body");

            List<LocalGitRepository.CommitInfo> log = phone.log(10, 0);
            assertEquals(2, log.size());
            assertEquals("Second", log.get(0).summary);
            assertEquals("Second\n\nWith a body", log.get(0).message);
            assertEquals(7, log.get(0).shortSha.length());
            assertEquals("Initial", log.get(1).summary);
            assertEquals(1, phone.log(1, 1).size());

            List<LocalGitRepository.FileDiff> diffs = phone.commitDiff(log.get(0).sha);
            assertEquals(3, diffs.size());
            for (LocalGitRepository.FileDiff diff : diffs) {
                if ("modify".equals(diff.changeType)) {
                    assertEquals("README.md", diff.newPath);
                    assertEquals("# Hello\n\nfirst line\n", text(diff.oldBytes));
                    assertEquals("# Hello\n\nsecond\n", text(diff.newBytes));
                } else if ("add".equals(diff.changeType)) {
                    assertNull(diff.oldPath);
                    assertNull(diff.oldBytes);
                    assertEquals("new.md", diff.newPath);
                } else {
                    assertEquals("delete", diff.changeType);
                    assertEquals("docs/guide.md", diff.oldPath);
                    assertNull(diff.newBytes);
                }
            }
            // The root commit diffs against the empty tree.
            assertEquals(2, phone.commitDiff(log.get(1).sha).size());
            assertFailsWith("COMMIT_NOT_FOUND", () -> phone.commitDiff("0000000000000000000000000000000000000000"));
        }
    }

    @Test
    public void binaryFilesAreFlaggedInsteadOfDiffed() throws Exception {
        try (LocalGitRepository phone = cloneDevice(createRemote(), "phone")) {
            phone.writeFile("image.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 0, 1, 2});
            phone.commitAll("Image");
            LocalGitRepository.FileDiff diff = phone.commitDiff(phone.log(1, 0).get(0).sha).get(0);
            assertTrue(diff.binary);
            assertNull(diff.newBytes);
        }
    }

    @Test
    public void revertingACommitUndoesItAsANewCommit() throws Exception {
        try (LocalGitRepository phone = cloneDevice(createRemote(), "phone")) {
            phone.writeFile("README.md", bytes("mistake"));
            phone.commitAll("Mistake");
            String mistake = phone.log(1, 0).get(0).sha;

            assertEquals("merged", phone.revertCommit(mistake).result);
            assertEquals("# Hello\n\nfirst line\n", text(phone.readFile("README.md", 64)));
            assertTrue(phone.log(1, 0).get(0).summary.startsWith("Revert"));
            assertEquals(3, phone.log(10, 0).size());
        }
    }

    @Test
    public void revertConflictsAreResolvedLikeAMerge() throws Exception {
        try (LocalGitRepository phone = cloneDevice(createRemote(), "phone")) {
            phone.writeFile("README.md", bytes("one"));
            phone.commitAll("One");
            String first = phone.log(1, 0).get(0).sha;
            phone.writeFile("README.md", bytes("two"));
            phone.commitAll("Two");

            LocalGitRepository.PullOutcome outcome = phone.revertCommit(first);
            assertEquals("conflicts", outcome.result);
            assertEquals(Collections.singletonList("README.md"), outcome.conflicts);
            assertEquals("REVERTING", phone.status().state);

            phone.resolveConflict("README.md", "ours");
            phone.completeMerge("Revert One, keep two");
            assertEquals("SAFE", phone.status().state);
            assertEquals("two", text(phone.readFile("README.md", 64)));
        }
    }

    @Test
    public void cloneFailuresLeaveNoHalfRepository() throws Exception {
        File target = new File(temporary.getRoot(), "broken");
        assertFailsWith("REMOTE_NOT_FOUND", () -> LocalGitRepository.cloneInto(
            target, new File(temporary.getRoot(), "missing.git").toURI().toString(), null, null, null));
        assertFalse(target.exists());
    }

    @Test
    public void writesReplaceFilesAtomically() throws Exception {
        try (LocalGitRepository phone = cloneDevice(createRemote(), "phone")) {
            phone.writeFile("docs/deep/new.md", bytes("deep"));
            assertArrayEquals(bytes("deep"), phone.readFile("docs/deep/new.md", 64));
            File[] siblings = new File(phone.getWorkTree(), "docs/deep").listFiles();
            assertEquals(1, siblings.length);
            assertFailsWith("DOCUMENT_TOO_LARGE", () -> phone.readFile("docs/deep/new.md", 2));
            assertFailsWith("DOCUMENT_NOT_FOUND", () -> phone.readFile("missing.md", 64));
        }
    }
}

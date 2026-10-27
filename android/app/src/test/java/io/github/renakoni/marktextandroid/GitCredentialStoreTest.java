package io.github.renakoni.marktextandroid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GitCredentialStoreTest {

    @Test
    public void serializationRoundTrips() {
        GitCredentialStore parsed =
            GitCredentialStore.parse(new GitCredentialStore("alice", "s3cr\"et").serialize());

        assertEquals("alice", parsed.username);
        assertEquals("s3cr\"et", parsed.password);
    }

    @Test
    public void emptyOrBrokenValuesReadAsNothingSaved() {
        assertNull(GitCredentialStore.parse(""));
        assertNull(GitCredentialStore.parse(null));
        assertNull(GitCredentialStore.parse("{not json"));
        assertNull(GitCredentialStore.parse("{\"username\":\"\",\"password\":\"\"}"));
    }

    @Test
    public void credentialsAreKeyedByHostAndExplicitPort() {
        assertEquals("gitlab.com", GitCredentialStore.hostKey("https://GitLab.com/group/project.git"));
        assertEquals("git.example.org:8443", GitCredentialStore.hostKey("https://git.example.org:8443/a/b.git"));
        assertEquals(
            GitCredentialStore.hostKey("https://gitlab.com/one.git"),
            GitCredentialStore.hostKey("https://gitlab.com/two/three.git")
        );
        assertNull(GitCredentialStore.hostKey("not a url"));
        assertNull(GitCredentialStore.hostKey(null));
    }

    @Test
    public void githubHostsAreLeftToTheGitHubAccount() {
        assertTrue(GitCredentialStore.isGitHubHost(GitCredentialStore.hostKey("https://github.com/o/r.git")));
        assertFalse(GitCredentialStore.isGitHubHost(GitCredentialStore.hostKey("https://github.example.com/o/r.git")));
    }
}

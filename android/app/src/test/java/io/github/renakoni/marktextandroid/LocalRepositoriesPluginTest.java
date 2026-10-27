package io.github.renakoni.marktextandroid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LocalRepositoriesPluginTest {

    @Test
    public void slugsMakeSafeDirectoryNames() {
        assertEquals("octocat-hello-world", LocalRepositoriesPlugin.slug("octocat/Hello World"));
        assertEquals("docs.site", LocalRepositoriesPlugin.slug("docs.site"));
        assertEquals("a.b", LocalRepositoriesPlugin.slug("a...b"));
        assertEquals("repository", LocalRepositoriesPlugin.slug("../.."));
        assertEquals("repository", LocalRepositoriesPlugin.slug("한글"));
        assertTrue(LocalRepositoriesPlugin.isValidId(LocalRepositoriesPlugin.slug("octocat/Hello World")));
    }

    @Test
    public void idsRejectTraversalAndOddNames() {
        assertTrue(LocalRepositoriesPlugin.isValidId("octocat-hello"));
        assertTrue(LocalRepositoriesPlugin.isValidId("notes-2"));
        assertFalse(LocalRepositoriesPlugin.isValidId(""));
        assertFalse(LocalRepositoriesPlugin.isValidId(null));
        assertFalse(LocalRepositoriesPlugin.isValidId(".."));
        assertFalse(LocalRepositoriesPlugin.isValidId("a/b"));
        assertFalse(LocalRepositoriesPlugin.isValidId(".hidden"));
        assertFalse(LocalRepositoriesPlugin.isValidId("Upper"));
        assertFalse(LocalRepositoriesPlugin.isValidId("a..b"));
    }
}

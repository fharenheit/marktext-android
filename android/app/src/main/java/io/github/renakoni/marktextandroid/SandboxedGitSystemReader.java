package io.github.renakoni.marktextandroid;

import java.io.File;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.SystemReader;

/**
 * Keeps JGit inside the app sandbox: its user, system and JGit-level
 * configs live in an app-private directory (empty unless the app writes
 * them), and "user.home" points there. The default reader would probe
 * $HOME, /etc/gitconfig and even spawn a `git` process to locate the
 * system config — none of which exist on Android, and on a developer
 * machine running the JVM tests they would leak the host's settings in.
 */
final class SandboxedGitSystemReader extends SystemReader {

    private final SystemReader delegate;
    private final File configDirectory;

    private SandboxedGitSystemReader(SystemReader delegate, File configDirectory) {
        this.delegate = delegate;
        this.configDirectory = configDirectory;
    }

    /** Installs once per process; later calls keep the first directory. */
    static synchronized void install(File configDirectory) {
        if (SystemReader.getInstance() instanceof SandboxedGitSystemReader) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        configDirectory.mkdirs();
        SystemReader.setInstance(new SandboxedGitSystemReader(SystemReader.getInstance(), configDirectory));
        FS.DETECTED.setUserHome(configDirectory);
    }

    @Override
    public String getHostname() {
        return "localhost";
    }

    @Override
    public String getenv(String variable) {
        // HOME, XDG_CONFIG_HOME, GIT_* overrides must not reach JGit.
        return null;
    }

    @Override
    public String getProperty(String key) {
        if ("user.home".equals(key)) {
            return configDirectory.getAbsolutePath();
        }
        return delegate.getProperty(key);
    }

    @Override
    public FileBasedConfig openUserConfig(Config parent, FS fs) {
        return new FileBasedConfig(parent, new File(configDirectory, "user.gitconfig"), fs);
    }

    /**
     * The system level also carries the app's defaults, which a repository's
     * own config may still override: core.symlinks=false checks symbolic
     * links out as small text files holding the target, so a cloned
     * {@code x -> ../..} can never lead file operations out of the
     * working tree.
     */
    @Override
    public FileBasedConfig openSystemConfig(Config parent, FS fs) {
        Config defaults = new Config(parent);
        defaults.setBoolean("core", null, "symlinks", false);
        return new FileBasedConfig(defaults, new File(configDirectory, "system.gitconfig"), fs);
    }

    @Override
    public FileBasedConfig openJGitConfig(Config parent, FS fs) {
        return new FileBasedConfig(parent, new File(configDirectory, "jgit.gitconfig"), fs);
    }

    @Override
    public long getCurrentTime() {
        return delegate.getCurrentTime();
    }

    @Override
    public int getTimezone(long when) {
        return delegate.getTimezone(when);
    }
}

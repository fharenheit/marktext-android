package io.github.renakoni.marktextandroid;

import java.net.URI;
import java.util.Locale;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Username and password (or token) for a non-GitHub Git host, remembered
 * per host after a successful clone so push and pull reuse them and the
 * clone form can prefill the username. Serialized into app-private
 * preferences, like the cloud tokens.
 */
final class GitCredentialStore {
    final String username;
    final String password;

    GitCredentialStore(String username, String password) {
        this.username = username;
        this.password = password;
    }

    /** Preference key for a remote: lowercased host plus an explicit port; null when the URL has no host. */
    static String hostKey(String url) {
        if (url == null) {
            return null;
        }
        try {
            URI uri = new URI(url.trim());
            String host = uri.getHost();
            if (host == null || host.isEmpty()) {
                return null;
            }
            String key = host.toLowerCase(Locale.ROOT);
            return uri.getPort() > 0 ? key + ":" + uri.getPort() : key;
        } catch (java.net.URISyntaxException ex) {
            return null;
        }
    }

    static boolean isGitHubHost(String hostKey) {
        return "github.com".equals(hostKey) || "www.github.com".equals(hostKey);
    }

    String serialize() {
        try {
            JSONObject json = new JSONObject();
            json.put("username", username);
            json.put("password", password);
            return json.toString();
        } catch (JSONException ex) {
            return "";
        }
    }

    static GitCredentialStore parse(String serialized) {
        if (serialized == null || serialized.isEmpty()) {
            return null;
        }
        try {
            JSONObject json = new JSONObject(serialized);
            String username = json.optString("username", "");
            String password = json.optString("password", "");
            return username.isEmpty() && password.isEmpty() ? null : new GitCredentialStore(username, password);
        } catch (JSONException ex) {
            return null;
        }
    }
}

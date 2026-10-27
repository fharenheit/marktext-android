package io.github.renakoni.marktextandroid;

import java.util.Collections;
import java.util.List;

/** Local Git operation failure with a stable code the web layer can branch on. */
final class LocalRepositoryException extends Exception {

    final String code;
    /** Files the failure concerns (conflicts, files that would be overwritten). */
    final List<String> paths;

    LocalRepositoryException(String code, String message) {
        this(code, message, Collections.emptyList(), null);
    }

    LocalRepositoryException(String code, String message, Throwable cause) {
        this(code, message, Collections.emptyList(), cause);
    }

    LocalRepositoryException(String code, String message, List<String> paths, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.paths = paths;
    }
}

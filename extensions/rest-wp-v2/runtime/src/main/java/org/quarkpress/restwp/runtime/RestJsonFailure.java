package org.quarkpress.restwp.runtime;

/**
 * Why a JSON request body could not be decoded, as PHP's {@code json_last_error} and {@code json_last_error_msg}
 * report it.
 */
public record RestJsonFailure(int code, String message) {

    static final RestJsonFailure DEPTH = new RestJsonFailure(1, "Maximum stack depth exceeded");
    static final RestJsonFailure STATE_MISMATCH = new RestJsonFailure(2, "State mismatch (invalid or malformed JSON)");
    static final RestJsonFailure CONTROL_CHARACTER =
            new RestJsonFailure(3, "Control character error, possibly incorrectly encoded");
    static final RestJsonFailure SYNTAX = new RestJsonFailure(4, "Syntax error");
    static final RestJsonFailure UTF8 =
            new RestJsonFailure(5, "Malformed UTF-8 characters, possibly incorrectly encoded");
    static final RestJsonFailure UTF16 = new RestJsonFailure(10, "Single unpaired UTF-16 surrogate in unicode escape");
}

package org.quarkword.lib.wpstyleengine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * One entry of {@code wp-parity-cases.json}: a function name, its JSON input and arguments, and the output WordPress
 * gives ({@code null} until recorded; a recorded JSON {@code null} is written {@code "out": null} and never needed).
 * {@code oracle} false marks a case that cannot be sent to php.
 */
record RecordedCase(String id, String fn, JsonNode in, ObjectNode args, JsonNode out, boolean oracle) {}

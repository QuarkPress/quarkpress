package org.quarkword.lib.wpformatting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * One entry of {@code wp-parity-cases.json}: a WordPress function name, its input and arguments, and the output
 * WordPress gives ({@code null} until recorded). {@code oracle} false marks a case that cannot be sent to php.
 */
record RecordedCase(String id, String fn, String in, ObjectNode args, JsonNode out, boolean oracle) {}

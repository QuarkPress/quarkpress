package org.quarkpress.testing.wprestparity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One media parity scenario: what a fresh subdirectory site holds before the steps ({@link Setup}), then the media
 * operations run on it in order ({@link Step}). The same scenario runs on WordPress and on QuarkPress, and {@link
 * MediaComparison} compares what each stored. {@code editor} marks a scenario that needs an image editor on the
 * WordPress side (PHP's GD): without one it cannot run there.
 *
 * <p>Refs name what the scenario creates, so both sides can be compared although their ids differ: a setup post's
 * {@code ref} ({@code p:...}), {@code step<n>} for the attachment upload step {@code n} (1-based) created, and
 * {@code u:<user>} for the five users.
 */
public record MediaScenario(String id, String rule, boolean editor, List<Setup> setup, List<Step> steps) {

    public MediaScenario {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(rule, "rule");
        setup = List.copyOf(setup);
        steps = List.copyOf(steps);
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("scenario " + id + " has no steps");
        }
    }

    /** A test file: the name of a {@link MediaFixtures} method (or one of {@link MediaScenarios#fixture}'s extras). */
    public record Fixture(String name, JsonNode args) {
        public Fixture {
            Objects.requireNonNull(name, "name");
            args = args == null ? NullNode.getInstance() : args;
        }
    }

    /** What the site holds before the steps. */
    public sealed interface Setup {

        /**
         * A post written by alice, titled {@code "Post <ref>"}; {@code date} is its local post date ({@code yyyy-MM-dd
         * HH:mm:ss}; the sites' time zone is UTC) when given, else now.
         */
        record Post(String ref, String type, String status, Optional<String> date) implements Setup {
            public Post {
                Objects.requireNonNull(ref, "ref");
                Objects.requireNonNull(type, "type");
                Objects.requireNonNull(status, "status");
                Objects.requireNonNull(date, "date");
            }
        }

        /**
         * A site option. Options named {@code qp_parity_...} are switches of WordPress's harness plugin: QuarkPress has
         * no such option and leaves them alone.
         */
        record Option(String name, JsonNode value) implements Setup {
            public Option {
                Objects.requireNonNull(name, "name");
                Objects.requireNonNull(value, "value");
            }
        }

        /** Files written into the site's current upload folder ({@code <year>/<month>}) before any upload. */
        record Folder(Map<String, Fixture> files) implements Setup {
            public Folder {
                files = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(files));
                for (String name : files.keySet()) {
                    if (name.isEmpty() || name.indexOf('/') >= 0 || name.startsWith(".")) {
                        throw new IllegalArgumentException("a folder file is a plain name: " + name);
                    }
                }
            }
        }

        /**
         * A network option, for the whole network: each side puts the previous value back when the scenario ends. A
         * JSON null removes the option.
         */
        record NetworkOption(String name, JsonNode value) implements Setup {
            public NetworkOption {
                Objects.requireNonNull(name, "name");
                Objects.requireNonNull(value, "value");
            }
        }
    }

    /** One media operation of the scenario. */
    public sealed interface Step {

        /** The caller: one of the five users. */
        String as();

        /**
         * An upload: {@code fixture}'s bytes sent as the {@code file} part named {@code clientName} with
         * {@code declaredType}, and the text {@code fields} (WordPress's REST names: {@code title}, {@code caption},
         * {@code description}, {@code alt_text}, {@code post}, {@code status}, {@code generate_sub_sizes},
         * {@code convert_format}). A {@code post} value that is a ref is replaced by that post's id.
         */
        record Upload(String as, Fixture fixture, String clientName, String declaredType, Map<String, String> fields)
                implements Step {
            public Upload {
                Objects.requireNonNull(as, "as");
                Objects.requireNonNull(fixture, "fixture");
                Objects.requireNonNull(clientName, "clientName");
                Objects.requireNonNull(declaredType, "declaredType");
                fields = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(fields));
            }
        }

        /**
         * An update of the attachment {@code ref} with the REST fields given ({@code title}, {@code caption},
         * {@code description}, {@code alt_text}, {@code post}); or, when {@code ref} is a setup post, its featured
         * image ({@code featured_media}: an attachment's ref).
         */
        record Update(String as, String ref, Map<String, String> fields) implements Step {
            public Update {
                Objects.requireNonNull(as, "as");
                Objects.requireNonNull(ref, "ref");
                fields = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(fields));
            }
        }

        /** Deletes the attachment for good, with its files. */
        record Delete(String as, String ref) implements Step {
            public Delete {
                Objects.requireNonNull(as, "as");
                Objects.requireNonNull(ref, "ref");
            }
        }

        /** WordPress's post-process of the attachment: makes the sizes it is missing. */
        record Process(String as, String ref) implements Step {
            public Process {
                Objects.requireNonNull(as, "as");
                Objects.requireNonNull(ref, "ref");
            }
        }

        /**
         * The site's attachments whose type matches {@code mimeTypes}, as WordPress's {@code post_mime_type} query
         * variable takes them: one value is sent as a string (commas and all), several as a list.
         */
        record Query(String as, List<String> mimeTypes) implements Step {
            public Query {
                Objects.requireNonNull(as, "as");
                mimeTypes = List.copyOf(mimeTypes);
                if (mimeTypes.isEmpty()) {
                    throw new IllegalArgumentException("a query names at least one MIME value");
                }
            }
        }
    }

    /** The ref of the attachment upload step {@code n} (1-based) creates. */
    public static String stepRef(int n) {
        return "step" + n;
    }
}

package org.quarkpress.restwp.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** WordPress's reading of {@code _embed}: absent embeds nothing; a false or "true" value everything; else a list. */
class EmbedParamTest {

    @Test
    void embedParsing() {
        assertThat(Embedder.parse(Optional.empty())).isEqualTo(EmbedChoice.NONE);
        for (String all : List.of("", "true", "1", "0", " , ")) {
            assertThat(Embedder.parse(Optional.of(all))).as(all).isEqualTo(EmbedChoice.ALL);
        }
        assertThat(Embedder.parse(Optional.of("up, author")).relations()).containsExactly("up", "author");
        assertThat(Embedder.parse(Optional.of("TRUE")).relations()).containsExactly("TRUE");
    }

    @Test
    void anArrayIsTakenItemByItemWithoutSplitting() {
        assertThat(Embedder.parse(PhpQuery.parse("_embed[]=up,author&_embed[]=wp:term")
                                .get("_embed"))
                        .relations())
                .containsExactly("up,author", "wp:term");
        // nested arrays are dropped; nothing left embeds everything
        assertThat(Embedder.parse(PhpQuery.parse("_embed[a][]=up").get("_embed")))
                .isEqualTo(EmbedChoice.ALL);
        assertThat(Embedder.parse(PhpQuery.parse("_embed=up").get("_embed")).relations())
                .containsExactly("up");
        assertThat(Embedder.parse((JsonNode) null)).isEqualTo(EmbedChoice.NONE);
    }

    @Test
    void eachHrefIsFetchedOncePerAnswerUpToTheBound() throws Exception {
        Map<String, Integer> fetches = new HashMap<>();
        Embedder embedder = new Embedder(
                EmbedChoice.ALL,
                href -> {
                    fetches.merge(href, 1, Integer::sum);
                    if (href.equals("nowhere")) {
                        return Optional.empty();
                    }
                    return Optional.of(
                            href.equals("empty")
                                    ? RestJson.NODES.arrayNode()
                                    : RestJson.NODES.objectNode().put("at", href));
                },
                3);
        JsonNode list = RestJson.READER.readTree("[{\"id\":1,\"_links\":{"
                + "\"up\":[{\"embeddable\":true,\"href\":\"a\"},{\"href\":\"x\"},{\"embeddable\":\"0\",\"href\":\"y\"}],"
                + "\"none\":[{\"embeddable\":true,\"href\":\"empty\"},{\"embeddable\":true,\"href\":\"nowhere\"}],"
                + "\"late\":[{\"embeddable\":true,\"href\":\"b\"},{\"embeddable\":\"false\",\"href\":\"c\"}]}},"
                + "{\"id\":2,\"_links\":{\"up\":[{\"embeddable\":1,\"href\":\"a\"}]}},"
                + "\"scalar\",{\"id\":3}]");
        JsonNode embedded = embedder.embed(list);
        // non-embeddable links (absent, "0") hold [] in their place; "false" is a true string in PHP
        assertThat(embedded.at("/0/_embedded/up").toString()).isEqualTo("[{\"at\":\"a\"},[],[]]");
        // a relation whose entries hold nothing is left out
        assertThat(embedded.at("/0/_embedded").has("none")).isFalse();
        // a, empty and b fill the bound of 3 fetched hrefs: c, fetched fourth, gets []
        assertThat(embedded.at("/0/_embedded/late").toString()).isEqualTo("[{\"at\":\"b\"},[]]");
        // the second item's link to a is not fetched again
        assertThat(embedded.at("/1/_embedded/up/0/at").asText()).isEqualTo("a");
        assertThat(fetches).containsEntry("a", 1).containsEntry("b", 1).doesNotContainKey("c");
        // items without links, and values that are not objects, are kept as they are
        assertThat(embedded.get(2).asText()).isEqualTo("scalar");
        assertThat(embedded.get(3).toString()).isEqualTo("{\"id\":3}");
        // the input is not changed
        assertThat(list.get(0).has("_embedded")).isFalse();
    }

    @Test
    void listedRelationsAreComparedAsGiven() throws Exception {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            many.add("r" + i);
        }
        many.add("wp:term");
        Embedder embedder = new Embedder(
                new EmbedChoice(EmbedChoice.Kind.THESE, many),
                href -> Optional.of(RestJson.NODES.objectNode().put("at", href)));
        ArrayNode list = RestJson.NODES.arrayNode();
        for (int i = 0; i < 100; i++) {
            list.add(RestJson.READER.readTree("{\"_links\":{\"wp:term\":[{\"embeddable\":true,\"href\":\"t" + i
                    + "\"}],\"https://api.w.org/term\":[{\"embeddable\":true,\"href\":\"u\"}]}}"));
        }
        JsonNode embedded = embedder.embed(list);
        assertThat(embedded.at("/99/_embedded/wp:term/0/at").asText()).isEqualTo("t99");
        // the names are compared as rendered: the long form of a compacted relation is not one of them
        assertThat(embedded.at("/0/_embedded").has("https://api.w.org/term")).isFalse();
    }
}

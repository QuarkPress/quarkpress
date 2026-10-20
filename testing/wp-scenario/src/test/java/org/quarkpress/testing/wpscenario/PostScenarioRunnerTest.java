package org.quarkpress.testing.wpscenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.quarkpress.posts.OrderKey;
import org.quarkpress.posts.PostException;

class PostScenarioRunnerTest {

    @Test
    void orderKeysMapToQuarkPressKeys() {
        assertThat(PostScenarioRunner.orderKey("menu_order")).isEqualTo(OrderKey.MENU_ORDER);
        assertThat(PostScenarioRunner.orderKey("post_in")).isEqualTo(OrderKey.POST_IN);
        assertThat(PostScenarioRunner.orderKey("id")).isEqualTo(OrderKey.ID);
        for (String key : QueryArgs.ORDER_KEYS) {
            assertThat(PostScenarioRunner.orderKey(key)).as(key).isNotNull();
        }
        assertThatThrownBy(() -> PostScenarioRunner.orderKey("rand")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void optionValuesTakeTheTypeWordPressSanitizingLeaves() {
        assertThat(PostScenarioRunner.optionValue("posts_per_page", "4")).isEqualTo(LongNode.valueOf(4));
        assertThat(PostScenarioRunner.optionValue("posts_per_page", "0")).isEqualTo(LongNode.valueOf(1));
        assertThat(PostScenarioRunner.optionValue("posts_per_page", "-5")).isEqualTo(LongNode.valueOf(5));
        assertThat(PostScenarioRunner.optionValue("posts_per_page", "-1")).isEqualTo(LongNode.valueOf(-1));
        assertThat(PostScenarioRunner.optionValue("posts_per_page", "7 days")).isEqualTo(LongNode.valueOf(7));
        assertThat(PostScenarioRunner.optionValue("posts_per_page", "x")).isEqualTo(LongNode.valueOf(1));
        // PHP 8's integer cast, as php -r prints it for these texts
        assertThat(PostScenarioRunner.optionValue("posts_per_page", "1e3")).isEqualTo(LongNode.valueOf(1000));
        assertThat(PostScenarioRunner.optionValue("posts_per_page", "99999999999999999999"))
                .isEqualTo(LongNode.valueOf(Long.MAX_VALUE));
        assertThat(PostScenarioRunner.optionValue("posts_per_page", " 12")).isEqualTo(LongNode.valueOf(12));
        assertThat(PostScenarioRunner.optionValue("posts_per_page", "2.9")).isEqualTo(LongNode.valueOf(2));
        assertThat(PostScenarioRunner.optionValue("posts_per_page", "-2.5e1")).isEqualTo(LongNode.valueOf(25));
        assertThat(PostScenarioRunner.optionValue("posts_per_page", ".5e1")).isEqualTo(LongNode.valueOf(5));
        assertThat(PostScenarioRunner.optionValue("posts_per_page", "1e400")).isEqualTo(LongNode.valueOf(1));
        assertThatThrownBy(() -> PostScenarioRunner.optionValue("posts_per_page", "-99999999999999999999"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(PostScenarioRunner.optionValue("gmt_offset", "5.5")).isEqualTo(TextNode.valueOf("5.5"));
        assertThat(PostScenarioRunner.optionValue("gmt_offset", "+5.5")).isEqualTo(TextNode.valueOf("5.5"));
        assertThat(PostScenarioRunner.optionValue("gmt_offset", "5:30")).isEqualTo(TextNode.valueOf(""));
        assertThat(PostScenarioRunner.optionValue("default_comment_status", "")).isEqualTo(TextNode.valueOf("closed"));
        assertThat(PostScenarioRunner.optionValue("default_ping_status", "0")).isEqualTo(TextNode.valueOf("closed"));
        assertThat(PostScenarioRunner.optionValue("default_ping_status", "open"))
                .isEqualTo(TextNode.valueOf("open"));
        assertThat(PostScenarioRunner.optionValue("timezone_string", "Europe/Paris"))
                .isEqualTo(TextNode.valueOf("Europe/Paris"));
    }

    @Test
    void aRefusalOfAPostThatIsGoneIsWordPressAnswerForTrashUntrashDeletePublishAndStick() {
        PostException gone = new PostException("invalid_post", "no post");
        assertThat(PostScenarioRunner.goneAnswer("trash_post", gone, true)).isEqualTo(Optional.of(Outcome.NOTHING));
        assertThat(PostScenarioRunner.goneAnswer("untrash_post", gone, true)).isEqualTo(Optional.of(Outcome.NOTHING));
        assertThat(PostScenarioRunner.goneAnswer("delete_post", gone, true)).isEqualTo(Optional.of(Outcome.NOTHING));
        assertThat(PostScenarioRunner.goneAnswer("stick", gone, true)).isEqualTo(Optional.of("ok"));
        assertThat(PostScenarioRunner.goneAnswer("publish_post", gone, true)).isEqualTo(Optional.of("ok"));
        assertThat(PostScenarioRunner.goneAnswer("update_post", gone, true)).isEmpty();
        // a post the scenario did not delete is not gone: QuarkPress's refusal stays a refusal
        assertThat(PostScenarioRunner.goneAnswer("trash_post", gone, false)).isEmpty();
        assertThat(PostScenarioRunner.goneAnswer("stick", gone, false)).isEmpty();
        assertThat(PostScenarioRunner.goneAnswer("publish_post", gone, false)).isEmpty();
        assertThat(PostScenarioRunner.goneAnswer("trash_post", new PostException("invalid_parent", "x"), true))
                .isEmpty();
    }
}

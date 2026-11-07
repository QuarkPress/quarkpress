package org.quarkpress.media.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.quarkpress.media.MediaException;
import org.quarkpress.media.runtime.UploadLimits.Space;

/** WordPress's multisite upload checks over REST: the first failing test wins, with WordPress's messages. */
class UploadLimitsTest {

    static final long MB = 1_048_576, KB = 1024;
    static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private static String message(Optional<MediaException> refusal) {
        assertThat(refusal).isPresent();
        return refusal.get().getMessage();
    }

    @Test
    void notEnoughSpace() {
        assertThat(UploadLimits.check(800 * KB, "1500", new Space(100 * MB, 99 * MB + MB / 2)))
                .hasValueSatisfying(e -> assertThat(e)
                        .extracting(MediaException::code, Throwable::getMessage)
                        .containsExactly("rest_upload_limited_space", "Not enough space to upload. 288 KB needed."));
        // exactly the space left passes
        assertThat(UploadLimits.check(512 * KB, "1500", new Space(100 * MB, 99 * MB + MB / 2)))
                .isEmpty();
    }

    @Test
    void tooBig() {
        assertThat(UploadLimits.check(1600 * KB, "1500", new Space(100 * MB, 2 * MB)))
                .hasValueSatisfying(e -> assertThat(e)
                        .extracting(MediaException::code, Throwable::getMessage)
                        .containsExactly(
                                "rest_upload_file_too_big",
                                "This file is too big. Files must be less than 1500 KB in size."));
        assertThat(UploadLimits.check(1500 * KB, "1500", new Space(100 * MB, 0)))
                .isEmpty();
        assertThat(UploadLimits.check(1500 * KB + 1, "1500", new Space(100 * MB, 0)))
                .isPresent();
    }

    @Test
    void theSpaceTestComesFirst() {
        assertThat(UploadLimits.check(1600 * KB, "1500", new Space(MB, 0)))
                .hasValueSatisfying(e -> assertThat(e.code()).isEqualTo("rest_upload_limited_space"));
    }

    @Test
    void theLimitIsPrintedAsStoredAndReadAsPhpArithmeticReadsIt() {
        // a fraction, a leading number with trailing text, an exponent: PHP multiplies by the leading number
        assertThat(message(UploadLimits.check(1537, "1.5", new Space(100 * MB, 0))))
                .isEqualTo("This file is too big. Files must be less than 1.5 KB in size.");
        assertThat(UploadLimits.check(1536, "1.5", new Space(100 * MB, 0))).isEmpty();
        assertThat(message(UploadLimits.check(12 * KB + 1, " 12abc", new Space(100 * MB, 0))))
                .isEqualTo("This file is too big. Files must be less than  12abc KB in size.");
        assertThat(UploadLimits.check(12 * KB, " 12abc", new Space(100 * MB, 0)))
                .isEmpty();
        assertThat(UploadLimits.check(1000 * KB, "1e3", new Space(100 * MB, 0))).isEmpty();
        assertThat(UploadLimits.check(1000 * KB + 1, "1e3", new Space(100 * MB, 0)))
                .isPresent();
        // a value without a leading number (a PHP type error in WordPress) refuses every non-empty file
        assertThat(message(UploadLimits.check(1, "abc", new Space(100 * MB, 0))))
                .isEqualTo("This file is too big. Files must be less than abc KB in size.");
        assertThat(UploadLimits.check(0, "abc", new Space(100 * MB, 0))).isEmpty();
    }

    @Test
    void theQuotaMessageNeedsAnEmptyFile() {
        assertThat(UploadLimits.check(0, "1500", new Space(100 * MB, 101 * MB)))
                .hasValueSatisfying(e -> assertThat(e)
                        .extracting(MediaException::code, Throwable::getMessage)
                        .containsExactly(
                                "rest_upload_user_quota_exceeded",
                                "You have used your space quota. Please delete files before uploading."));
        // exactly at the quota is not over it
        assertThat(UploadLimits.check(0, "1500", new Space(100 * MB, 100 * MB))).isEmpty();
        assertThat(UploadLimits.check(1, "1500", new Space(100 * MB, 101 * MB)))
                .hasValueSatisfying(e -> assertThat(e.code()).isEqualTo("rest_upload_limited_space"));
    }

    @Test
    void aNegativeQuotaLeavesNoSpaceAndPutsTheSiteOverIt() {
        // the negative quota counts as 0 only for the space left; the quota test sees it as it is
        assertThat(UploadLimits.check(0, "1500", new Space(-5 * MB, 0)))
                .hasValueSatisfying(e -> assertThat(e.code()).isEqualTo("rest_upload_user_quota_exceeded"));
        assertThat(message(UploadLimits.check(2048, "1500", new Space(-5 * MB, 0))))
                .isEqualTo("Not enough space to upload. 2 KB needed.");
        assertThat(UploadLimits.check(0, "1500", new Space(Long.MIN_VALUE, Long.MAX_VALUE)))
                .hasValueSatisfying(e -> assertThat(e.code()).isEqualTo("rest_upload_user_quota_exceeded"));
        // a zero quota: an empty file passes while nothing is stored, and is over quota once something is
        assertThat(UploadLimits.check(0, "1500", new Space(0, 0))).isEmpty();
        assertThat(UploadLimits.check(0, "1500", new Space(0, 1)))
                .hasValueSatisfying(e -> assertThat(e.code()).isEqualTo("rest_upload_user_quota_exceeded"));
    }

    @Test
    void numbersAreFormattedAsPhpsNumberFormat() {
        assertThat(UploadLimits.numberFormat(1234.5)).isEqualTo("1,235");
        assertThat(UploadLimits.numberFormat(288)).isEqualTo("288");
        assertThat(UploadLimits.numberFormat(1_234_567.49)).isEqualTo("1,234,567");
        assertThat(UploadLimits.numberFormat(76.5)).isEqualTo("77");
        assertThat(UploadLimits.numberFormat(999.5)).isEqualTo("1,000");
        assertThat(UploadLimits.numberFormat(0.0009765625)).isEqualTo("0");
        assertThat(UploadLimits.numberFormat(2.5)).isEqualTo("3");
        assertThat(UploadLimits.numberFormat(1e20)).isEqualTo("100,000,000,000,000,000,000");
    }

    @Test
    void wordPresssRecordedRefusals() {
        assertThat(message(UploadLimits.check(1_126_912, "1500", new Space(MB, 0))))
                .isEqualTo("Not enough space to upload. 77 KB needed.");
        assertThat(message(UploadLimits.check(2_306_867, "1500", new Space(MB, 205))))
                .isEqualTo("Not enough space to upload. 1,229 KB needed.");
        assertThat(message(UploadLimits.check(1_537_024, "1500", new Space(2 * MB, 0))))
                .isEqualTo("This file is too big. Files must be less than 1500 KB in size.");
        assertThat(UploadLimits.check(1_536_000, "1500", new Space(2 * MB, 0))).isEmpty();
        assertThat(message(UploadLimits.check(1, "1500", new Space(2 * MB, 2 * MB + 200_000))))
                .isEqualTo("Not enough space to upload. 0 KB needed.");
    }

    @Test
    void theChecksFollowTheConfigurationThenTheOption() {
        assertThat(UploadLimits.checksOn(Optional.empty(), Optional.of(JSON.textNode("1"))))
                .isFalse();
        assertThat(UploadLimits.checksOn(Optional.empty(), Optional.of(JSON.numberNode(1))))
                .isFalse();
        assertThat(UploadLimits.checksOn(Optional.empty(), Optional.of(JSON.booleanNode(true))))
                .isFalse();
        for (JsonNode off : new JsonNode[] {
            JSON.textNode("0"), JSON.textNode(""), JSON.numberNode(0), JSON.booleanNode(false), JSON.nullNode()
        }) {
            assertThat(UploadLimits.checksOn(Optional.empty(), Optional.of(off)))
                    .as("%s", off)
                    .isTrue();
        }
        assertThat(UploadLimits.checksOn(Optional.empty(), Optional.empty())).isTrue();
        assertThat(UploadLimits.checksOn(Optional.of(true), Optional.of(JSON.textNode("1"))))
                .isTrue();
        assertThat(UploadLimits.checksOn(Optional.of(false), Optional.of(JSON.textNode("0"))))
                .isFalse();
    }

    @Test
    void theAllowedSpaceIsTheSitesNumericOptionThenTheNetworksThenOneHundredMegabytes() {
        Optional<JsonNode> none = Optional.empty();
        assertThat(UploadLimits.allowedBytes(Optional.of(JSON.textNode("2")), Optional.of(JSON.textNode("1"))))
                .isEqualTo(2 * MB);
        assertThat(UploadLimits.allowedBytes(Optional.of(JSON.numberNode(3)), Optional.of(JSON.numberNode(1))))
                .isEqualTo(3 * MB);
        assertThat(UploadLimits.allowedBytes(Optional.of(JSON.textNode("lots")), Optional.of(JSON.textNode("1"))))
                .isEqualTo(MB);
        assertThat(UploadLimits.allowedBytes(none, Optional.of(JSON.numberNode(1))))
                .isEqualTo(MB);
        assertThat(UploadLimits.allowedBytes(none, Optional.of(JSON.textNode(" 1.5"))))
                .isEqualTo(MB + MB / 2);
        assertThat(UploadLimits.allowedBytes(none, Optional.of(JSON.booleanNode(true))))
                .isEqualTo(100 * MB);
        assertThat(UploadLimits.allowedBytes(none, Optional.of(JSON.textNode(""))))
                .isEqualTo(100 * MB);
        assertThat(UploadLimits.allowedBytes(none, none)).isEqualTo(100 * MB);
        // a negative quota is kept as it is: it leaves no space and the site is over it
        assertThat(UploadLimits.allowedBytes(Optional.of(JSON.textNode("-5")), none))
                .isEqualTo(-5 * MB);
        assertThat(UploadLimits.allowedBytes(Optional.of(JSON.textNode("-1e300")), none))
                .isEqualTo(Long.MIN_VALUE);
        assertThat(UploadLimits.allowedBytes(Optional.of(JSON.textNode("1e300")), none))
                .isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void theLimitTextIsTheOptionAsPhpPrintsIt() {
        assertThat(UploadLimits.maxK(Optional.of(JSON.textNode("1500")))).isEqualTo("1500");
        assertThat(UploadLimits.maxK(Optional.of(JSON.numberNode(1500)))).isEqualTo("1500");
        assertThat(UploadLimits.maxK(Optional.of(JSON.numberNode(1.5)))).isEqualTo("1.5");
        assertThat(UploadLimits.maxK(Optional.empty())).isEqualTo("1500");
    }
}

package org.quarkword.lib.wpformatting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;
import org.junit.jupiter.api.Test;

/** force_balance_tags and balanceTags; every expected output is WordPress's answer from the case file. */
class BalanceTagsTest {

    private static final List<RecordedCase> CASES = RecordedCases.load();

    /** Checks the recorded case {@code id}: its input is {@code input} and both entry points give its output. */
    private static void assertCase(String id, String input) {
        RecordedCase c = CASES.stream()
                .filter(x -> x.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no case " + id));
        assertThat(c.fn()).as("fn of %s", id).isEqualTo("force_balance");
        assertThat(c.in()).as("input of %s", id).isEqualTo(input);
        assertThat(c.out()).as("recorded output of %s", id).isNotNull();
        assertThat(Formatting.forceBalanceTags(input)).as(id).isEqualTo(c.out().asText());
        assertThat(Formatting.balanceTags(input, true, false))
                .as("%s forced", id)
                .isEqualTo(c.out().asText());
    }

    @Test
    void unterminatedTag() { // Review Focus 3: a tag without ">" is text; tags inside an open comment still count
        assertCase("t7-1", "<b");
        assertCase("t7-2", "a <!-- <i>");
    }

    @Test
    void optionGate() { // FA7, A-G124, A-G125
        assertThat(Formatting.balanceTags("<b>x", false, true)).isEqualTo("<b>x</b>");
        assertThat(Formatting.balanceTags("<b>x", true, false)).isEqualTo("<b>x</b>");
        assertThat(Formatting.balanceTags("<b>x", true, true)).isEqualTo("<b>x</b>");
        String untouched = "<br><p>a</b><3";
        assertThat(Formatting.balanceTags(untouched, false, false)).isSameAs(untouched);
    }

    @Test
    void voidTags() { // FA52, FA53, A-G111
        assertCase(
                "t7-void-list",
                "<area><base><basefont><br><col><command><embed><frame><hr><img><input><isindex><link><meta><param>"
                        + "<source><track><wbr><keygen>");
        assertCase("t7-void-slash-space", "<br  />");
        assertCase("t7-void-attr-slash", "<br x/>");
        assertCase("t7-void-attr-space-slash", "<br x / >");
        assertCase("t7-void-slash-then-attr", "<br/ x>");
        assertCase("t7-void-newline", "<img\nsrc=a>");
        assertCase("t7-void-newline-slash", "<img\n\nsrc=a / >");
        assertCase("t7-void-closer", "<br>x</br>");
        assertCase("t7-void-case", "<BR><Br/><IMG SRC=x>");
    }

    @Test
    void emptyElementsAndAttributeTrim() { // FA52, FA53, A-G112
        assertCase("t7-empty-element-ws", "<div  x/><div\nx /><div x/ ><div / ><div  / ><div />");
        assertCase("t7-attr-trim", "<div  x  ><div \u0000><div x\f><div x\u0000><div x\u000b><div \f><b \u0000 x>");
        assertCase("t7-ws-before-gt", "<b  >x<a\r>y<b\f>z");
        assertCase("t7-nul-after-name", "<b\u0000>x<a b>y");
        assertCase("t7-uni-space", "<b >x<b\u0085>y<b　>z");
        assertCase("t7-crlf", "<p\r\nclass=x>a\r\n</p\r\n><br\r\n/>");
    }

    @Test
    void nestableTags() { // FA52, FA53, A-G113 to A-G115
        assertCase(
                "t7-nestable",
                "<article><aside><blockquote><details><figure><object><q><section><span><div>".repeat(2));
        assertCase("t7-not-nestable", "<a><a><b><b><p><p><li><li><td><td><x><x>");
        assertCase("t7-top-only", "<p><b><p>x");
        assertCase("t7-list", "<li><ul><li>a</ul><li>b</li><li>c");
        assertCase("t7-table", "<table><tr><td>a<td>b</tr><tr><td>c</table>");
    }

    @Test
    void caseVariants() { // FA52, A-G119
        assertCase("t7-nest-case", "<DIV><div><P><p><B><b><q><Q>");
        assertCase("t7-closer-forms", "<b>1</b x><b>2</b/><b>3</b / ><b>4</B ><b>5</b\n><b>6</b\f>");
    }

    @Test
    void malformedClosers() { // FA53, A-G109, A-G110, A-G123
        assertCase("t7-closer-swallows", "<b>x</b <i>y");
        assertCase("t7-closer-not-tags", "<b>x</ B></>y</A-b><//b>");
        assertCase("t7-closer-nearest", "<i><b><i><u>x</i>y");
        assertCase("t7-closer-stray", "<b>a</i>c</b></b></b>z");
        assertCase("t7-closer-crossed", "<b><i></b></i>x");
        assertCase("t7-queue-order", "<b><i>a</b>c<u>d<p>x<p>y");
    }

    @Test
    void lessThanInAttributes() { // FA51, A-G120
        assertCase("t7-lt-in-attr", "<a title=\"x>y\">t<a b=\"<i>\">u<a <b>v");
        assertCase("t7-lt-in-attr-trailing", "<div title=\"x>y\" >");
        assertCase("t7-attr-slash-in-quote", "<div x=\"a/>");
    }

    @Test
    void tagNames() { // FA51, A-G121, A-G122
        assertCase("t7-names", "<a3>1<my-el>2<a-b>3<a.b-c>4<a_b-c>5<:>6<_>7<a:b>8");
        assertCase("t7-not-names", "<My-el>1<my-El>2<1-a>3<a->4<é>5<aé>6<a.b>7< b>8<>9</>");
        assertCase("t7-surrogates", "<b>é😀</b>x<i>中");
    }

    @Test
    void commentsAndEscapes() { // FA50, FA51, A-G116 to A-G118
        assertCase("t7-digits", "<3a><<3</3><b>a<3</b><a x=<3>t");
        assertCase("t7-comment-escape", "< !--<b>< !-- x --> <    !-- y --><     !--< !--< !--");
        assertCase("t7-markup-kinds", "<!--<b>--><!----><![CDATA[<i>]]><?php <u> ?>");
        assertCase("t7-comment-closer", "<b><!-- </b> -->x");
    }

    @Test
    void longCustomNameTailKeepsBalancing() { // D11: WordPress stops balancing from 18 characters after the hyphen
        assertCase("t7-custom-tail-17", "<b>x<a-" + "b".repeat(17) + "=<i>y");
        assertThat(Formatting.forceBalanceTags("<b>x<a-" + "b".repeat(18) + "=<i>y"))
                .isEqualTo("<b>x<a-" + "b".repeat(18) + "=<i>y</i></b>");
        assertThat(Formatting.forceBalanceTags("<b>x</a-" + "b".repeat(40) + "=<i>y<p>z"))
                .isEqualTo("<b>x</a-" + "b".repeat(40) + "=<i>y<p>z</p></i></b>");
        String longTail = "<a-" + "b".repeat(1_000_000) + "=<i>y";
        assertThat(Formatting.forceBalanceTags(longTail)).isEqualTo(longTail + "</i>");
    }

    @Test
    void edgesAndNulls() {
        assertCase("t7-empty", "");
        assertCase("t7-gt-only", "a>b<");
        assertThatNullPointerException()
                .isThrownBy(() -> Formatting.forceBalanceTags(null))
                .withMessage("text");
        assertThatNullPointerException()
                .isThrownBy(() -> Formatting.balanceTags(null, true, true))
                .withMessage("text");
        assertThatNullPointerException()
                .isThrownBy(() -> Formatting.balanceTags(null, false, false))
                .withMessage("text");
    }
}

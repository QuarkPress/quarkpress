<?php
/*
 * Copyright 2026 The QuarkPress Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Writes the reference examples (src/test/resources/examples/*.json): each case's "expected" value is
 * what WordPress returns for it. Inputs are the verified examples of the kses behavior reference plus
 * a few probes. Regenerate after a WordPress bump and review the diff:
 *   php src/test/php/kses-examples.php <wordpress-src-dir> src/test/resources/examples
 */

require __DIR__ . '/kses-cli.php';

if (3 !== $argc) {
    fwrite(STDERR, "usage: php kses-examples.php <wordpress-src-dir> <output-dir>\n");
    exit(2);
}
$E = rtrim($argv[2], '/') . '/';

/** Characters that are hard to see in a diff are written as \u escapes. */
function qp_visible(string $json): string
{
    return preg_replace_callback(
        '/[\x{7F}-\x{A0}\x{AD}\x{2000}-\x{200F}\x{2028}-\x{202F}\x{FEFF}]/u',
        fn ($m) => sprintf('\\u%04x', unpack('N', iconv('UTF-8', 'UTF-32BE', $m[0]))[1]),
        $json
    );
}

/** Writes one file; $groups maps an id prefix to a list of [fn, arguments]. */
function qp_write(string $file, array $groups): void
{
    $lines = [];
    foreach ($groups as $label => $cases) {
        $n = 0;
        foreach ($cases as [$fn, $args]) {
            $n++;
            $case = ['id' => "$label-$n", 'fn' => $fn] + $args;
            $case['expected'] = qp_kses_case($case);
            $lines[] = '  ' . qp_visible(json_encode(
                $case,
                JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE | JSON_THROW_ON_ERROR
            ));
        }
    }
    file_put_contents($file, "[\n" . implode(",\n", $lines) . "\n]\n");
}

/** One case per text, all with the same extra arguments. */
function t(string $fn, array $texts, array $extra = []): array
{
    return array_map(fn ($text) => [$fn, ['text' => $text] + $extra], $texts);
}

qp_write($E . 'no-null.json', [
  'R2.1' => t('no_null', ["a\0b\x01c\x08d\te\x0Bf\x0Cg\rh\x0Ei\x1Fj\x7Fk"], ['keep' => false]),
  'R2.2' => t('no_null', ["a\\0b\\00c\\\\0d\\0x", "\\\\a\\0", "a\\b\\", "0\\0"], ['keep' => false]),
  'R2.3' => t('no_null', ["x\\0y \\0 <b title=\"a\\0b\">z</b>", "x\0y\x01z"], ['keep' => true]),
]);
qp_write($E . 'text-utils.json', [
  'R13.4' => t('stripslashes', ['a\"b', "a\\'b", "a\\\\\"b", '<a title=\"x\">q']),
  'R15.2' => t('html_error', ['abc def', '"a b" c', "'a b' c", 'a"b c"d e', '"unterminated x', '   lead', '', "x\ny"]),
]);
qp_write($E . 'decode-attribute.json', [
  'R8.4' => t('decode_attribute', ['&amp;', '&amp', '&ampx', '&amp=', '&amp;x', '&notit;', '&notin;', '&not', '&noti', '&#65', '&#65x', '&#x41g', '&#0;', '&#xD800;', '&#x110000;', '&#128;', '&#x81;', '&#x9F;', '&#9999999999;', '&#;', '&#x;', '&', '&;', '&Rarr;', '&RightArrow;', '&CounterClockwiseContourIntegral;', '&nbsp', '&nbspx', '&lt', '&ltx', '&#13;', '&NotNestedGreaterGreater;', "a\r\nb\rc", '&#0000000065;', '&#x0000000000041;', '&nbsp;x&copy;&hellip;&rarr;', '&#X41;&#x0;']),
]);
qp_write($E . 'normalize.json', [
  'R3-html' => t('normalize_entities', ['&#65; &#065; &#0000065; &#00000000065;', '&#58;', '&#0; &#00; &#8; &#9; &#10; &#13; &#31; &#32;', '&#1114111; &#1114112; &#9999999; &#99999999;', '&#x41; &#X41; &#x0041; &#x000000041;', '&#x3a; &#x3A;', '&#xD800; &#xFFFE; &#xFFFF; &#x10FFFF; &#x110000;', '&#XYZZY; &#; &#x; &#xG1;', 'AT&T', '&amp; &lt; &gt; &quot; &apos; &nbsp;', '&hellip; &rarr; &euro; &trade;', '&Hellip; &AMP; &NBSP;', '&copy &copy;', '&a; &ab; &abcdefgh; &abcdefghi; &abcdefgh12; &abcdefgh123; &ab1; &a1b;', '&sup2; &frac12; &sup1;', '& alone; &&amp;', '&amp;amp; &amp;#65; &amp;lt;', '&#38;#x41;', 'no references'], ['xml' => false]),
  'R3-xml' => t('normalize_entities', ['&hellip; &rarr; &euro; &trade;', '&sup2; &frac12; &sup1;', '&nbsp;', '&amp; &lt; &gt; &quot; &apos;', '&copy &foo; &Hellip;'], ['xml' => true]),
]);
qp_write($E . 'escape.json', [
  'esc' => t('esc_attr', ['&apos; &amp; &copy; &#039; &#39; &#x27; &#1114111; &foo; &#0; a<b>"c" &hellip; &AMP; &amp', '&#x10FFFF; &#xD800; &#65 &#128; &sup2; &Rarr;', "it's \"q\"", 'plain', '', '<a href="x">']),
]);
qp_write($E . 'bad-protocol.json', [
  'R10.2' => t('bad_protocol', ['https://x'], ['protocols' => ['HTTPS']]),
  'R10.3' => t('bad_protocol', ['a:b:c:d:e', 'a:b:c:d:e:f', 'a:b:c:d:e:f:g', 'a:b:c:d:e:f:g:h', 'a:b:c:d:e:f:http://x', 'javascript:javascript:alert(1)']),
  'R10.4' => t('bad_protocol', ['javascript:alert(1)', 'JAVASCRIPT:alert(1)', 'javascript :alert(1)', '  javascript:alert(1)', 'javascript&#58;alert(1)', 'javascript&#058alert(1)', 'javascript&#x3a;x', 'javascript&#X3A;x', 'javascript&#x0003a;x', 'javascript&#x0000003A;x', 'javascript&COLON;x', 'javascript&Colon;x', '/?q=a:b', '/x/?q=a:b', 'http:/?x:y', 'mailto:x?a:b', '/path?q=a:b', '?a:b', '#a:b', 'path/x:y', 'a:b', 'x:http://ok', 'http:x:y', 'https://a:b@c', 'MAILTO:a@b', 'Mailto:A@B', 'HTTP://X', 'feed:feed:http://x', 'feed:feed:feed:http://x', 'feed:javascript:x', 'feed:x', 'feed:0', 'feed:feed:0', 'javascript:', ':', ':nothing', 'javascript&#5800;x', 'javascript&#x3a3;x', 'no separator']),
  'R10.5' => t('bad_protocol', ["java\0script:alert(1)", "java\\0script:alert(1)", "java\x01script:alert(1)", "java\nscript:alert(1)", '&#106;avascript:x', '&#x6a;avascript:x', '&#X6A;avascript:x', '&#0106;avascript:x', '&#362;avascript:x', ' http://lead', 'http ://x', 'h&#116;tp://x', '&#38;#x68;ttp://x', '&#x7FFFFFFFFFFFFF6A;avascript:x', '&#x10000000000006A;avascript:x']),
  'R10.9' => array_merge(t('bad_protocol', ['FTP://x'], ['protocols' => ['FTP']]), t('bad_protocol', ['http://x', 'mailto:a'], ['protocols' => ['https']])),
]);
qp_write($E . 'hair.json', [
  'R8.1' => t('hair', ['class="is-wide" inert data-lazy=\'&lt;img&#00062\' =/🐮=/', 'A=1 a=2 B', '"q"=1', '', '   ', '/ /', 'x=">', 'a="1" b=\'2', '==x', "t=\"a\r\nb\"", 'x=a=b y=`c` z=\'d"e\'', ' / title="x"/ ']),
  'R8.5' => t('hair', ['href="javascript:x" src=javascript:y title="javascript:z" cite=" vbscript:q"', 'title="&lt;&amp;&#39;&quot;"']),
]);
qp_write($E . 'safecss.json', [
  'R12.1' => t('safecss', ["\tcolor:\tred", "color: red\n;background:blue", "color: \"\0red\""]),
  'R12.3' => t('safecss', ["color:red; behavior:url(javascript:x)\n"], ['properties' => []]),
  'R12.4' => t('safecss', ['color: red', 'color:red; background-color: blue;', 'COLOR: red', 'foo: bar; color: red', 'color', 'color: red; nonsense', ';;;', 'color : red ;  ; margin:0', ' ; width:1px', 'width:10px;', "font-family: 'a;b'", 'color: red !important', 'color: #fff', 'display: none', 'position: fixed; z-index: 99999', 'margin: 0 auto !important; padding : 1em', 'content: "x"', 'behavior: url(x.htc)', '-moz-binding: url(x)', 'color: expression(alert(1))', '', '0', 'color:red;0', 'x:1;y:2']),
  'R12.5' => t('safecss', ['--my-var: 10px', '--my_var: 1', '--: 1', '-- x: 1', '--my-var: url(https://x/a.png)', '--my-var: url(javascript:x)', '--my-var: linear-gradient(red, blue)', '--x: calc(1px)', '--x: rgb(1,2,3)', '--x: a&b', '--x: a url(b)']),
  'R12.6' => t('safecss', ['background: url(https://x.org/a.png)', 'background: url("https://x.org/a.png") no-repeat', "background: url('a.png')", 'background: url(  https://x.org/a.png  )', 'background: url(a b)', "background: url(\"a.png')", 'background: url(javascript:alert(1))', 'background: url()', 'background: url("")', 'background: url(x:y)', 'background: url(#x) url(/a?b=1:2)', 'background: url(/?b=1:2)', 'background: url(data:image/png;base64,AAA)', 'background-image: url(a.png), url(b.png)', 'background-image: url(a.png) , url(javascript:x)', 'background: url(https://a/b.png?x=1&y=2)', 'color: url(a.png)', 'list-style-image: url(a.png)', 'cursor: url(a.cur), auto', 'clip-path: url(#clip)', 'fill: url(#grad)', 'stroke: url(javascript:x)', 'background-image: url(a.png)/*x*/', 'background: url(0)', 'background: url( 0 )', 'background: url("0")', 'background: url(a) url(0)', 'background: url(url(a)', 'background: URL(a.png)', 'background: url( "a" "b" )']),
  'R12.7' => t('safecss', ['background-image: linear-gradient(to right, red, blue)', 'background-image: linear-gradient(to right, rgb(1,2,3), hsl(1 2% 3%))', 'background-image: linear-gradient(to right, rgb(1,calc(2+1),3), blue)', 'background: repeating-radial-gradient(circle, red, blue), url(a.png)', 'background: conic-gradient(red, blue)', 'background:linear-gradient(red,blue)url(a.png)', 'background-image: -webkit-linear-gradient(red, blue)', 'background: linear-gradient(red, url(javascript:x))', 'background: linear-gradient(y) linear-gradient(x linear-gradient(y) z)', 'background: linear-gradient(red', 'background: Linear-gradient(red, blue)']),
  'R12.8' => t('safecss', ['width: calc(100% - 10px)', 'width: calc(100% - (2 * 10px))', 'width: calc(100% - (2 * (3px)))', 'color: var(--x)', 'color: var(--x, var(--y, red))', 'grid-template-columns: repeat(3, minmax(0, 1fr))', 'width: clamp(1rem, 2vw, 3rem)', 'width: min(10px, 5vw)', 'width: max(10px,5vw)', 'transform: rotate(45deg) translateX(10px)', 'transform: rotatex(45deg)', 'width: CALC(1px)', 'width: xcalc(1px)', 'width: -webkit-calc(1px)', 'clip-path: polygon(0 0, 100% 0, 50% 100%)', 'width: calc(1px & 2)', "width: var(=}\\5c)", 'width: calc(/* c */ 1px)', 'width: calc(1px', 'width: calc(1px))', 'color: rgb(1,2,3)', 'filter: blur(2px)', 'filter: url(#f) blur(2px)', 'width: fit-content(10px)', 'background: url(a.png) rgb(1,2,3)', 'width: 10px; height: 20px /* c */', 'width: 10px/*c*/', 'margin: 0 & 1', 'margin: a=b', 'margin: a}b', "margin: a\\62 c", 'aspect-ratio: 16/9', 'text-decoration: underline wavy', 'color:red;color:blue', 'transform: rotate3d(1,1,1,45deg) scale3d(1,1,1)', 'width: calc_(1px)', 'width: 3calc(1px)']),
]);
$P = ['mode' => 'bare', 'context' => 'post'];
$pdf = 'https://example.org/wp-content/uploads/2026/10/a.pdf';
$g = ['a' => ['href' => true, 'title' => false, 'rel' => '', 'name' => null, 'x-len' => ['maxlen' => 3], 'x-min' => ['minlen' => 2], 'x-maxv' => ['maxval' => 10], 'x-minv' => ['minval' => 5], 'x-vl' => ['valueless' => 'y'], 'x-nvl' => ['valueless' => 'N'], 'x-vals' => ['values' => ['one', 'two']], 'data-*' => ['maxlen' => 2]]];
$probe = ['a' => ['x-vals' => ['values' => ['1', 'one']], 'x-maxv' => ['maxval' => 10], 'x-len' => ['MAXLEN' => 3]]];
$obf = ['java&#115;cript:alert(1)', 'javascript&#58;alert(1)', 'javascript&colon;alert(1)', '&#106;&#97;&#118;&#97;&#115;&#99;&#114;&#105;&#112;&#116;&#58;alert(1)', 'jav&#x09;ascript:alert(1)', ' javascript:alert(1)', 'java script:alert(1)', "java\tscript:alert(1)", '&#x6A;avascript:x', '&#x6A;&#x61;vascript&#x3A;x', 'javas&#99;ript&colon;x', 'javascript&amp;colon;x', 'ja&Tab;vascript:x', '&#x20;javascript:x', 'javascript&#x3a;&#x3a;x', 'javascript&#0000058alert(1)', '&#0000106avascript:x', 'javascript&#x3Aalert(1)', 'https:/&#x2f;ok'];
qp_write($E . 'kses-bare.json', [
  'R1.3' => t('kses', ['<script>alert(1)</script>', '<foo>bar</foo>', '<p>one<p>two', '<div><p>unbalanced</div>', '<b>a</i>', '<B>Up</B><DIV ALIGN="center">x</DIV>', '<B title="T" ONCLICK="x">y</B>', '<svg><circle/></svg>'], $P),
  'R1.5' => t('kses', ['a > b', '', 'plain text'], $P),
  'R2.3' => t('kses', ["x\\0y \\0 <b title=\"a\\0b\">z</b>", "x\0y\x01z", "<b\0>bold</b>", "<scr\0ipt>alert(1)</script>"], $P),
  'R4.3' => t('kses', ['a < b', 'a <b c', 'x <3 y', '<b', '<b>x</b', '<b><', '<<b>>', '<a href="x"', '<a title="x', '<!-- unclosed', '<!-- <script>x</script> -->', '<![CDATA[ <b>x</b> ]]>', '<!-- a <!-- b --> c -->', '<p>1 < 2 and 3 > 2</p>'], $P),
  'R4.4' => t('kses', ['<!-- wp:paragraph {"x":"<script>a</script><b>b</b>"} --><p>t</p><!-- /wp:paragraph -->', '<!-- wp:p {"a":"<script>"} /-->'], $P),
  'R5.2' => t('kses', ["<!-- a\nb -->", "<!-- a > \nb -->", "<!-- a\nb", "<!-- a\n", "x<!-- a\nb <b>c</b>", "<!--x-->\n"], $P),
  'R6.1' => t('kses', ['<a title=\"x\">q \"y\"</a>'], $P),
  'R6.2' => t('kses', ['<b>>', '<a href="x">1</a>>', '>>>'], $P),
  'R6.3' => t('kses', ['</3>', '</ x>', '</#x>', '</ >', '<!doctype html>', '<!foo>', '<!foo bar <b>x</b>>', '<!a<script>x</script>>', '</%x<b>y</b>>', '</ <script>x</script>>', '</>>', '<!a<b>>'], $P),
  'R6.4' => t('kses', ['<!-- comment -->', '<!--a-->', '<!-- -->', '<!---->', '<!---->x<!---->', '<!-- a -- b --- c -->', '<!-- --- -->', '<!-- - - -->', '<!-- x --->', '<!-- x ---->', '<!-- a > b -->', '<!-- a&b -->', '<!-- &lt;script&gt; -->', '<!-- <b title=\"t\" onclick=x>t</b> -->', '<!--> x', '<!---> x', '<!-- x -- > y -->', '<!-- <!-- --> -->', '<!-- -->-->', "<!-- a\nb -\n", "<!-- a -\n", "<!-- a\n-->"], $P),
  'R6.5' => t('kses', ['<!DOCTYPE html>', '<!>', '<! x>', '<![CDATA[x]]>', '<?php echo 1; ?>', '<?xml version="1.0"?>', '<%= x %>', '<:::>', '</>', '<>', '< >', 'x<y', 'x<1', 'x<-y', 'x</y', 'a<<b', '<  b>x', '< / b >'], $P),
  'R6.6' => t('kses', ['<a:b>x</a:b>', '<bé>x</bé>', '<p_x>y</p_x>', '<a.b href="z">y</a.b>', '<b-x>y</b-x>', '<h7>y</h7>', '<1>', '<h1-x>'], $P),
  'R6.8' => t('kses', ['</b foo="bar">', '</B>', '</b/>', '</ b>'], $P),
  'R7.1' => t('kses', ['<b/>', '<br/>', '<br    / >', '<hr noshade>', '<img src=x.png/>'], $P),
  'R7.2' => array_merge(t('kses', ['<b class="c">x</b>'], ['mode' => 'bare', 'allowed' => ['b' => true]]), t('kses', ['<i class="c">x</i>'], ['mode' => 'bare', 'allowed' => ['i' => []]])),
  'R7.4' => array_merge(
     t('kses', ["<object data=\"$pdf\" type=\"application/pdf\"></object>", '<object data="https://evil.org/a.pdf" type="application/pdf">x</object>', '<object type="application/pdf">x</object>', "<object data=\"$pdf\" type=\"text/html\"/>", "<object data=\"$pdf\" type=\"application/PDF\"></object>", "<object data=\"$pdf?x\" type=\"application/pdf\">x</object>"], $P),
     t('kses', ['<img alt="a">', '<img alt="a" />', '<img src="javascript:x">', '<img src="" alt="a">'], ['mode' => 'bare', 'allowed' => ['img' => ['src' => ['required' => true], 'alt' => true]]]),
     t('kses', ['<span title="t">x</span>', '<span class="c" onclick="z" title="t">x</span>'], ['mode' => 'bare', 'allowed' => ['span' => ['class' => ['required' => true], 'title' => true]]])),
  'R7.5' => t('kses', ['<a href="x" href="y">dup</a>', '<a HREF="x" Title="t">case</a>', "<a href='single'>q</a>", '<a href=unq>q</a>', '<a href = "spaced" >s</a>', '<img src="a.png" alt="a" onerror="x">', '<img src="a.png" width="10" height="20" / >', '<br clear="all">', '<a download="file">dl</a>', '<a download>dl</a>'], $P),
  'R8.1' => t('kses', ['<p/class="slash">s</p>', '<img/src="a.png">', '<a href="x"/title="t">y</a>', '<img src="a.png"alt="b">', '<p class="a"class="b">s</p>', '<a title="x"y="z">n</a>', '<p "quoted">q</p>', '<p =x>eq</p>', '<p class=a=b>eq2</p>', '<a title=a"b>n</a>', '<a title=`x`>n</a>', '<a title= "x">y</a>', '<a title=>y</a>', '<a title= >y</a>', "<p\tclass=\"tab\">t</p>", "<p\tclass=\"x\"\n>y</p>", "<a\rtitle=\"x\">y</a>", '<a title="x" title="y" TITLE="z">y</a>'], $P),
  'R8.2' => t('kses', ['<a title=">">gt</a>', '<a title="x>y">gt2</a>', '<a title="<script>">n</a>', '<a href="x" title="y', '<b<i>x</i>'], $P),
  'R8.3' => t('kses', ["<a title=\"a\r\nb\rc\">y</a>", "text\r\nmore"], $P),
  'R8.4' => t('kses', ['<a title="&nbsp;x&copy;&hellip;&rarr;">n</a>', '<a title="&foo; &amp &copy &notit; &#0; &#x110000;">n</a>', '<a title="&Rarr; &rarr; &RightArrow;">y</a>', '<a title="&#X41;&#x0;">y</a>', '<a rel="&#x6A;">y</a>', "<a title=\"a&#10;b\tc\">n</a>", '<a title="&#128;&#150;&#13;">y</a>'], $P),
  'R8.5' => t('kses', ['<a title="a&b">e</a>', '<a title="&lt;b&gt;">e</a>', "<a title='he said \"hi\"'>e</a>", "<a title=\"it's\">e</a>", '<a title="&#x27;&#34;">ent</a>', '<a href="http://x.org/?a=1&b=2">l</a>', '<a href="http://x.org/?a=1&amp;b=2">l</a>', '<a href="http://x.org/?a=1&#38;b=2">l</a>', '<p class="&quot;onmouseover=x">x</p>', '<a title>bool</a>', '<a title="">empty</a>', '<p data-foo>x</p>'], $P),
  'R9.2' => t('kses', ['<a href="x" title="t" rel="r" name="n">'], ['mode' => 'bare', 'allowed' => $g]),
  'R9.3' => array_merge(t('kses', ['<p class="a b" id="c" data-foo="1" data-Bar="2" data-x_y="3" data-="4" data-ä="5" aria-label="L" aria-foo="no" role="note" tabindex="1" hidden lang="en" xml:lang="en" dir="rtl">x</p>', '<p DATA-FOO="x" data-foo.bar="y" data-foo:bar="z">x</p>', '<p DATA-É="x" data-ok="1">y</p>'], $P), t('kses', ['<a data-ab="12" data-cd="123">'], ['mode' => 'bare', 'allowed' => $g])),
  'R9.5' => t('kses', ["<p style=\"font-family: 'Open Sans'\">x</p>", "<p style=\"font-family: 'Open Sans'; foo: bar\">x</p>", '<p style="font-family: &quot;A&quot;; foo: bar">x</p>', '<p style="color: red; background: url(&quot;javascript:x&quot;)">x</p>', '<p style="background: url(&quot;https://x/a.png&quot;)">x</p>', '<p style="color:red;&#x3b;color:blue">x</p>', '<p style="color:red; a&amp;b: c">x</p>', "<p style=\"content:'&lt;'; color:red\">x</p>", '<p style="foo:bar">x</p>', '<p style>x</p>', '<p style="  ">x</p>', '<span style="">e</span>', '<p style="0">x</p>', '<p style="0;color:red">x</p>', '<p onmouseover="x" style="color:red;behavior:url(x)">s</p>', '<p style="font-family: 🐮">x</p>'], $P),
  'R9.6' => array_merge(
     t('kses', ['<a x-len="abc" x-min="ab">', '<a x-len="abcd">', '<a x-min="a">', '<a x-maxv="10" x-minv="5">', '<a x-maxv="11">', '<a x-maxv=" 7 ">', '<a x-maxv="-1">', '<a x-maxv="1.5">', '<a x-maxv="0000001">', '<a x-maxv="1234567">', '<a x-minv="abc">', '<a x-vl>', '<a x-vl="">', '<a x-vl="y">', '<a x-nvl>', '<a x-nvl="">', '<a x-vals="ONE">', '<a x-vals="three">', '<a x-vals=" one">'], ['mode' => 'bare', 'allowed' => $g]),
     t('kses', ['<a x-vals="01">', '<a x-vals="1.0">', '<a x-maxv="7&#10;">', '<a x-maxv="7      &#10;">', '<a x-maxv="7       &#10;">', '<a x-maxv="7&#10;&#10;">', '<a x-len="&amp;">', '<a x-len="ab">', '<a x-len="éé">'], ['mode' => 'bare', 'allowed' => $probe]),
     t('kses', ['<a title="&" rel="&lt;">x</a>'], ['mode' => 'bare', 'allowed' => ['a' => ['title' => ['maxlen' => 3], 'rel' => ['values' => ['&lt;']]]]]),
     t('kses', ['<a title="🐮">x</a>'], ['mode' => 'bare', 'allowed' => ['a' => ['title' => ['maxlen' => 3]]]]),
     t('kses', ['<a title="🐮">x</a>'], ['mode' => 'bare', 'allowed' => ['a' => ['title' => ['maxlen' => 4]]]])),
  'R9.7' => t('kses', ['<u title="t">x</u>', '<U title="t">x</U>'], ['mode' => 'bare', 'allowed' => ['U' => ['Title' => true]]]),
  'R10.6' => t('kses', ['<a href="data:text/html,x">d</a>', '<img src="data:image/png;base64,AAA">', '<a href="vbscript:x">v</a>', '<a href="mailto:a@b.c">m</a>', '<a href="#top">t</a>', '<a href="/rel">r</a>', '<a href="tel:+1">t</a>', '<a href="urn:isbn:1">u</a>'], $P),
  'R10.7' => t('kses', ['<a cite="javascript:x" title="javascript:x">x</a>', '<p data-href="javascript:x">x</p>', '<img longdesc="vbscript:x" usemap="#m">', '<blockquote cite="javascript:x">q</blockquote>'], $P),
  'R10.8' => t('kses', array_map(fn($v) => "<a href=\"$v\">x</a>", $obf), $P),
  'R10.9' => array_merge(t('kses', ['<a href="http://x">a</a><p style="background:url(http://y/a.png)">p</p><a href="mailto:a">m</a>'], $P + ['protocols' => ['https']]), t('kses', ['<a href="FTP://x">a</a>'], $P + ['protocols' => ['FTP']])),
  'R11.2' => array_merge(
     t('kses', ['<a href="x" title="t">x</a> <b>b</b>', 'a > b &amp; c &foo; <3'], ['mode' => 'bare', 'context' => 'strip']),
     t('kses', ['<a href="x" title="t" rel="r" target="_blank" class="c">x</a><p>p</p>'], ['mode' => 'bare', 'context' => 'user_description']),
     t('kses', ['<a href="x" title="t" rel="r" target="_blank" class="c">x</a><p>p</p>'], ['mode' => 'bare', 'context' => 'pre_term_description']),
     t('kses', ['<a href="x" title="t" rel="r" target="_blank" class="c">x</a><p>p</p>'], ['mode' => 'bare', 'context' => 'pre_user_description']),
     t('kses', ['<a href="x" title="t" rel="r" target="_blank" class="c">x</a><p>p</p>'], ['mode' => 'bare', 'context' => 'data']),
     t('kses', ['<a href="x" title="t" rel="r" target="_blank" class="c">x</a><p>p</p>'], ['mode' => 'bare', 'context' => 'title_save_pre']),
     t('kses', ['<b>x</b> &hellip;', '< /5>x', '< / 252 >y', '< /253>z', '< /00>w', '< /-1>v'], ['mode' => 'bare', 'context' => 'entities']),
     t('kses', ['<a href="x">x</a>'], ['mode' => 'bare', 'allowed' => []]),
     t('kses', ['<form action="/x"><input name="q"></form>'], $P)),
  'R11.3' => t('kses', ['<math display="block"><mi mathvariant="bold" onclick="x">x</mi></math>', '<mi aria-label="x" title="t" style="color:red" onclick="y">x</mi>', '<ul role="list" popover>x</ul>', '<button type="submit" popovertarget="p" onclick="x">b</button>', '<td colspan="2" rowspan="3" nowrap>x</td>', '<a href="x" rel="nofollow" rev="r" name="n" target="_blank">x</a>', '<iframe src="x"></iframe><embed src="y"><input name="z">'], $P),
  'focus' => t('kses', ['<a title="🐮 x" href="https://例え.jp/🐮">🐮</a>', "<p\r\nclass=\"x\">y</p>", "<!-- a\r\nb -->", "<!-- a\r\n", "a\r\n<b\r\n>c</b\r\n>"], $P),
]);
$W = ['mode' => 'wp', 'context' => 'post'];
qp_write($E . 'kses-wp.json', [
  'R4.3' => t('kses', ['a < b', 'a <b c', 'x <3 y', '<b', '<b>x</b', '<b><', '<<b>>', '<a href="x"', '<a title="x', '<!-- unclosed', '<!-- <script>x</script> -->', '<![CDATA[ <b>x</b> ]]>', '<!-- a <!-- b --> c -->', '<p>1 < 2 and 3 > 2</p>', "a <b\n", "it's <\"q\"> & <x"], $W),
  'R4.4' => t('kses', ['<!-- wp:paragraph --><p onclick="x">t</p><!-- /wp:paragraph -->', '<!-- wp:p {"a":"<script>"} /-->', '<!-- wp:template-part {"tagName":"script"} /-->', '<!-- wp:image {"url":"javascript:alert(1)"} /-->', '<!-- wp:p {"a":"x"} --->', '<!-- wp:paragraph {"x":"<script>a</script><b>b</b>"} --><p>t</p><!-- /wp:paragraph -->'], $W),
  'blocks' => t('kses', ['<!-- wp:template-part {"tagName":true} /-->', '<!-- wp:template-part {"tagName":"DIV"} /-->', '<!-- wp:template-part {"tagName":"div","a":{"tagName":"script"}} /-->', '<!-- wp:template-part {"tagName":"0"} /-->', '<!-- wp:template-part {"tagName":""} /-->', '<!-- wp:template-part {"tagName":5} /-->', '<!-- wp:template-part {"tagName":[]} /-->', '<!-- wp:core/template-part {"tagName":"section"} /-->', '<!-- wp:p {"tagName":"script"} /-->', '<!-- wp:p {"\\u003cx\\u003ea":1,"a":2} /-->', '<!-- wp:p {"a":2,"\\u003cx\\u003ea":1} /-->', '<!-- wp:p {"1\\u003cx\\u003e":"a"} /-->', '<!-- wp:p {"0\\u003cx\\u003e":"a","1":"b"} /-->', '<!-- wp:p {"a":["\\u003cscript\\u003e",1,"\\u003cb\\u003ex"]} /-->', '<!-- wp:p {"a":"\\u003cb"} /-->', '<!-- wp:p {"a":"x \\u003c y"} /-->', '<!-- wp:p {"a":"x > y"} /-->', '<!-- wp:p {"a":"&copy"} /-->', 'a<!-- wp:p --><p>x</p><!-- wp:q /-->y<!-- /wp:p -->b', '<!-- wp:p {bad json} --><p>x</p><!-- /wp:p -->', '<p>a</p><!-- /wp:p -->b', '<!-- wp:p {"a":"\\u003cb\\u003ex"} --><!-- wp:q {"b":"\\u003cscript\\u003e"} /--><!-- /wp:p -->', '<!-- wp:a -->x', '<!-- wp:a {"n":1.5,"t":true,"z":null} /-->', "<!-- wp:p {\"a\":\"x\"} ---><!-- x\n--->", '<!-- wp:p --><!-- wp:q --->x<!-- /wp:p -->', '<!-- wp:p {"a":"\\u003c!-- c --\\u003e"} /-->'], $W),
  'R4.5' => array_merge(t('kses', ['<span class="wp-note-mention user-1" title="t">x</span>', '<span class="a">x</span>'], ['mode' => 'wp', 'context' => 'pre_comment_content']), t('kses', ['<span class="a">x</span>'], ['mode' => 'bare', 'context' => 'pre_comment_content'])),
  'focus' => t('kses', ['<a title="🐮">x</a>', "<p\r\nclass=\"x\">y</p>"], $W),
]);
qp_write($E . 'one-attr.json', [
  'R14.1' => array_merge(t('one_attr', ['href="javascript:alert(1)"', 'href=javascript:x', 'href="  javascript:x"', 'href="javascript&#58;x"', 'href="java&#115;cript:x"', 'href="java&Tab;script:x"', "href='javascript&colon;x'", ' title="a<b" ', "title='x'", 'title=x y', 'title = "x"', 'title=', 'title=x"y', 'title="unterminated', "title='mixed\"", 'onclick="x"', 'href', 'download', 'title="&amp; &foo; "q""', 'HREF="http://x"', 'style="color:red;foo:bar"', 'style="foo:bar"', 'style="font-family:&quot;A&quot;"', 'data-x="1"', '  ', ' onclick="x" ', "\ttitle=\"a\"\n", "title=\"a\" \n", '="x"', 'TITLE', 'title="&apos;"', 'href="&#x6A;avascript:x"', 'style="color:red;x:y" ', 'title="', "title='"], ['element' => 'a']),
     t('one_attr', ['title="x" data-foo="javascript:x"'], ['element' => 'span']),
     t('one_attr', ['title="x"'], ['element' => 'script']),
     t('one_attr', ['href=x', 'TITLE="t"'], ['element' => 'A']),
     t('one_attr', ['data="https://example.org/wp-content/uploads/2026/10/a.pdf"', 'data="https://evil.org/a.pdf"', 'type="application/pdf"'], ['element' => 'object'])),
]);
qp_write($E . 'strip.json', [
  'R15.5' => array_merge(t('strip_all_tags', ['<p>Hello <b>world</b></p>', '<script>alert(1)</script>text<style>p{}</style>', '<SCRIPT type="x">a</SCRIPT>b', "<script>\nx\n</script>y", '<script>x</script >y', '<script>a</style>b</script>c', '<script>a', 'a < b > c', 'a <b c', 'a<b', 'a <1 b> c', 'a <<b>> c', 'a <=b> c', 'x <a title=">">y</a> z', 'x <!-- a > b --> y', 'x <?php echo 1; ?> y', 'x <% y %> z', 'x <!-- unterminated', 'x &amp; y', "<p>a</p>\n<p>b</p>", '  <p> x </p>  ', '12', 'a <b title="x>y">c</b> d', "a <b title='>'>c", 'a <b <i>x</i>> c', 'a <!DOCTYPE html> b', 'a <!-- x -- > y --> b', 'a <!-x> b', '<?xml version="1.0"?>x', 'a <?php echo ">"; ?> b', 'a<? ( > ) ?>b', 'a >b', "a <\tb> c", 'a <b\"c>d', 'x<', '<', 'a <a href="x>y', 'a <!a "b>" c> d', '<!-- a -->', 'a<!-->b-->c', '1 <2> 3 >4', '<scripts>x</scripts>y', '<style type="a">x</STYLE>y', "a\0b", "<?xml x ?>y<a>z"], ['remove_breaks' => false]),
     t('strip_all_tags', ["<p>a</p>\n\n<p>b\tc</p>", "a \r\n\t b", " x "], ['remove_breaks' => true])),
]);

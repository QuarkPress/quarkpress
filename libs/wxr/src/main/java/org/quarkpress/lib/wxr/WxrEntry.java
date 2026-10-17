package org.quarkpress.lib.wxr;

import java.util.Map;

/**
 * One top-level entry of a WXR channel, in document order. {@link #rawFields()} holds the decoded text of every
 * simple field as written, keyed by canonical name ({@code "wp:post_id"}, {@code "title"}, {@code
 * "dc:creator"}; unknown leaf elements as {@code "{uri}local"}; the guid attribute as {@code
 * "guid@isPermaLink"}), so nothing is lost when a typed field cannot hold the text (an integer field reading
 * {@code "12abc"} is null, its text stays here). When a field occurs twice, the first occurrence counts.
 */
public sealed interface WxrEntry permits WxrAuthor, WxrCategory, WxrTag, WxrTerm, WxrItem {

    Map<String, String> rawFields();
}

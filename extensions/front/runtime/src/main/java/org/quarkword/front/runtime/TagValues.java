package org.quarkword.front.runtime;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The substitution of a permalink structure's tags: each {@code %name%} whose value is known is replaced, as
 * WordPress's {@code get_permalink} replaces its fixed list of tags; {@code %postname%} and {@code %pagename%} stay
 * literal when the editor's template is wanted; a tag without value stays as it is. Nothing here reads the database.
 */
public final class TagValues {

    private TagValues() {}

    /**
     * The six date tags of a post's local date, as {@code post_date} splits them: {@code year} with four digits,
     * {@code monthnum}, {@code day}, {@code hour}, {@code minute} and {@code second} with two.
     */
    public static Map<String, String> date(LocalDateTime date) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("year", pad(date.getYear(), 4));
        values.put("monthnum", pad(date.getMonthValue(), 2));
        values.put("day", pad(date.getDayOfMonth(), 2));
        values.put("hour", pad(date.getHour(), 2));
        values.put("minute", pad(date.getMinute(), 2));
        values.put("second", pad(date.getSecond(), 2));
        return values;
    }

    /**
     * {@code structure} with every {@code %name%} of {@code values} replaced by its value; {@code postname} and
     * {@code pagename} are skipped when {@code leaveName}.
     */
    public static String substitute(String structure, Map<String, String> values, boolean leaveName) {
        String result = structure;
        for (Map.Entry<String, String> value : values.entrySet()) {
            String name = value.getKey();
            if (leaveName && (name.equals("postname") || name.equals("pagename"))) {
                continue;
            }
            result = result.replace("%" + name + "%", value.getValue());
        }
        return result;
    }

    private static String pad(int number, int width) {
        String digits = Integer.toString(number);
        StringBuilder out = new StringBuilder(width);
        for (int i = digits.length(); i < width; i++) {
            out.append('0');
        }
        return out.append(digits).toString();
    }
}

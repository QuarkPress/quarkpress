package org.quarkword.posts.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Week numbers as WordPress's week condition compares them: MySQL's {@code WEEK(date, 1)} for {@code start_of_week}
 * 1, {@code WEEK(date, 0)} for 0 and any value outside 1 to 6, and {@code WEEK(date - n days, 0)} for 2 to 6. Every
 * expected number was answered by MySQL 9.5 ({@code SELECT WEEK(...)}, recorded in the task report).
 */
class WeeksTest {

    private static LocalDate day(String text) {
        return LocalDate.parse(text);
    }

    @Test
    void numbersAsMySqlAnswersThem() {
        // SELECT WEEK('2026-05-20', 0), WEEK('2026-05-20', 1), WEEK('2026-01-04', 0), WEEK('2026-01-04', 1),
        //   WEEK('2027-01-01', 1), WEEK(DATE_SUB('2026-01-04', INTERVAL 3 DAY), 0) → 20 21 1 1 0 0
        assertThat(Weeks.number(day("2026-05-20"), 0)).isEqualTo(20);
        assertThat(Weeks.number(day("2026-05-20"), 1)).isEqualTo(21);
        assertThat(Weeks.number(day("2026-01-04"), 0)).isEqualTo(1);
        assertThat(Weeks.number(day("2026-01-04"), 1)).isEqualTo(1);
        assertThat(Weeks.number(day("2027-01-01"), 1)).isEqualTo(0);
        assertThat(Weeks.number(day("2026-01-04"), 3)).isEqualTo(0);
        // WEEK('2025-05-21',1), WEEK('2026-05-10',1), WEEK('2025-12-29',1), WEEK('2024-12-31',1), WEEK('2023-01-01',0),
        // WEEK('2024-01-01',1), WEEK('2021-01-03',1), WEEK('2021-01-04',1), WEEK('2022-01-01',0), WEEK('2020-12-31',1),
        // WEEK('2026-12-31',0) → 21 19 53 53 1 1 0 1 0 53 52
        assertThat(Weeks.number(day("2025-05-21"), 1)).isEqualTo(21);
        assertThat(Weeks.number(day("2026-05-10"), 1)).isEqualTo(19);
        assertThat(Weeks.number(day("2025-12-29"), 1)).isEqualTo(53);
        assertThat(Weeks.number(day("2024-12-31"), 1)).isEqualTo(53);
        assertThat(Weeks.number(day("2023-01-01"), 0)).isEqualTo(1);
        assertThat(Weeks.number(day("2024-01-01"), 1)).isEqualTo(1);
        assertThat(Weeks.number(day("2021-01-03"), 1)).isEqualTo(0);
        assertThat(Weeks.number(day("2021-01-04"), 1)).isEqualTo(1);
        assertThat(Weeks.number(day("2022-01-01"), 0)).isEqualTo(0);
        assertThat(Weeks.number(day("2020-12-31"), 1)).isEqualTo(53);
        assertThat(Weeks.number(day("2026-12-31"), 0)).isEqualTo(52);
    }

    @Test
    void startOfWeekOutsideOneToSixCountsFromSunday() {
        // WordPress's _wp_mysql_week: 1 → WEEK(d, 1); 2-6 → WEEK(DATE_SUB(d, INTERVAL n DAY), 0); anything else →
        // WEEK(d, 0)
        for (int other : new int[] {0, 7, 8, -1}) {
            assertThat(Weeks.number(day("2026-05-20"), other)).isEqualTo(20);
        }
        assertThat(Weeks.number(day("2026-05-20"), 2)).isEqualTo(Weeks.number(day("2026-05-18"), 0));
        assertThat(Weeks.number(day("2026-05-20"), 6)).isEqualTo(Weeks.number(day("2026-05-14"), 0));
    }

    @Test
    void theDaysOfAWeekNumberWithinAYearAreOneRange() {
        // ISO-style week 21 of 2026 (start_of_week 1) is 18-24 May; of 2025, 19-25 May
        assertThat(Weeks.range(2026, 21, 1)).contains(new Weeks.Range(day("2026-05-18"), day("2026-05-25")));
        assertThat(Weeks.range(2025, 21, 1)).contains(new Weeks.Range(day("2025-05-19"), day("2025-05-26")));
        // week 0 holds the days before the first week; 2027 starts on a Friday, so 1-3 January are week 0
        assertThat(Weeks.range(2027, 0, 1)).contains(new Weeks.Range(day("2027-01-01"), day("2027-01-04")));
        // the last week is cut at the year's end: 2025-12-29 to 2025-12-31 are week 53 of 2025
        assertThat(Weeks.range(2025, 53, 1)).contains(new Weeks.Range(day("2025-12-29"), day("2026-01-01")));
        // 2026 starts on a Thursday, so it has no week 0 under start_of_week 1, and no week 54 anywhere
        assertThat(Weeks.range(2026, 0, 1)).isEmpty();
        assertThat(Weeks.range(2026, 54, 1)).isEmpty();
        // a shifted start counts the shifted date, so the range moves by the shift: week 0 of 2026 under 3 holds
        // 4 to 6 January, whose dates three days earlier are 1 to 3 January (WEEK(DATE_SUB('2026-01-04', INTERVAL 3
        // DAY), 0) is 0)
        assertThat(Weeks.range(2026, 0, 3)).contains(new Weeks.Range(day("2026-01-04"), day("2026-01-07")));
    }

    @Test
    void everyDayOfARangeHasItsNumber() {
        for (int startOfWeek = 0; startOfWeek <= 7; startOfWeek++) {
            for (int year = 2019; year <= 2029; year++) {
                for (int week = 0; week <= 53; week++) {
                    int sow = startOfWeek;
                    int w = week;
                    Weeks.range(year, week, startOfWeek).ifPresent(range -> {
                        for (LocalDate d = range.from(); d.isBefore(range.until()); d = d.plusDays(1)) {
                            assertThat(Weeks.number(d, sow))
                                    .as("%s under %s", d, sow)
                                    .isEqualTo(w);
                        }
                        assertThat(Weeks.number(range.from().minusDays(1), sow)).isNotEqualTo(w);
                        assertThat(Weeks.number(range.until(), sow)).isNotEqualTo(w);
                    });
                }
            }
        }
    }
}

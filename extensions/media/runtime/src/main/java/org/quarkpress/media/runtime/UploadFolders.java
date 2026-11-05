package org.quarkpress.media.runtime;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;
import org.quarkpress.core.SiteId;
import org.quarkpress.posts.PostSnapshot;

/**
 * The folder an upload is stored in, as WordPress lays out its uploads: the files of every site other than the main
 * one under {@code sites/<id>}, then {@code <year>/<month>} of the upload's time unless the site turned year and month
 * folders off.
 */
public final class UploadFolders {

    /** The folder WordPress's refusal messages name for the uploads base. */
    static final String UPLOADS = "wp-content/uploads";

    private UploadFolders() {}

    /**
     * An upload folder.
     *
     * @param baseKey the site's uploads base in the storage: empty for the main site, {@code sites/<id>} for another
     * @param subdir the folder under the base: {@code <year>/<month>}, or empty without year and month folders
     */
    public record Folder(String baseKey, String subdir) {

        public Folder {
            Objects.requireNonNull(baseKey, "baseKey");
            Objects.requireNonNull(subdir, "subdir");
        }

        /** The folder's storage key: the base and the subdir joined; empty for the main site's base itself. */
        public String folderKey() {
            return join(baseKey, subdir);
        }

        /** The storage key of the file {@code name} in this folder. */
        public String key(String name) {
            return join(folderKey(), name);
        }

        /** The path of the file {@code name} relative to the site's uploads base (WordPress's attached file). */
        public String attachedFile(String name) {
            return join(subdir, name);
        }

        /** The folder as WordPress's refusal messages name it: {@code wp-content/uploads} and the folder key. */
        String described() {
            return join(UPLOADS, folderKey());
        }

        private static String join(String first, String second) {
            if (first.isEmpty()) {
                return second;
            }
            return second.isEmpty() ? first : first + "/" + second;
        }
    }

    /**
     * The folder of an upload to {@code site} at the local time {@code when}.
     *
     * @param mainSiteId the main site's id, whose files have no {@code sites/<id>} folder
     * @param yearMonth whether the site keeps its uploads in year and month folders
     */
    public static Folder of(SiteId site, long mainSiteId, boolean yearMonth, LocalDateTime when) {
        String base = site.value() == mainSiteId ? "" : "sites/" + site.value();
        String subdir = yearMonth ? year(when.getYear()) + "/" + twoDigits(when.getMonthValue()) : "";
        return new Folder(base, subdir);
    }

    /**
     * The local time whose year and month name an upload's folder: the parent's date when the upload has a parent
     * that is not a page and whose year is above 0, else now in the site's zone.
     */
    public static LocalDateTime when(Optional<PostSnapshot> parent, ZoneId zone, Instant now) {
        if (parent.isPresent() && !parent.get().type().equals("page")) {
            LocalDateTime date = parent.get().localDate(zone);
            if (date.getYear() > 0) {
                return date;
            }
        }
        return LocalDateTime.ofInstant(now, zone);
    }

    /** The year as WordPress writes it in a folder name: at least four digits. */
    private static String year(int year) {
        String digits = Integer.toString(year);
        return "0".repeat(Math.max(0, 4 - digits.length())) + digits;
    }

    private static String twoDigits(int value) {
        return value < 10 ? "0" + value : Integer.toString(value);
    }
}

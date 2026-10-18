package org.quarkpress.it.multisite.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;
import org.quarkpress.persistence.runtime.SiteScopedEntity;

/** A tag of a note; the database key to the note includes the site, so a tag cannot point at another site's note. */
@Entity
@Table(name = "it_note_tag")
@Filter(name = SiteScopedEntity.FILTER)
public class ItNoteTag extends SiteScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "note_id", nullable = false)
    public ItNote note;

    @Column(nullable = false, length = 50)
    public String label;

    public static ItNoteTag on(ItNote note, String label) {
        ItNoteTag tag = new ItNoteTag();
        tag.note = note;
        tag.label = label;
        return tag;
    }
}

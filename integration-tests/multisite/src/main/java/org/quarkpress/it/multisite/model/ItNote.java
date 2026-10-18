package org.quarkpress.it.multisite.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;
import org.quarkpress.persistence.runtime.SiteScopedEntity;

@Entity
@Table(name = "it_note")
@Filter(name = SiteScopedEntity.FILTER)
public class ItNote extends SiteScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, length = 100)
    public String text;

    public static ItNote of(String text) {
        ItNote note = new ItNote();
        note.text = text;
        return note;
    }
}

package org.quarkpress.it.content.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.Filter;
import org.quarkpress.persistence.runtime.SiteScopedEntity;

/** A site-scoped parent with a collection of site-scoped children, for the collection check. */
@Entity
@Table(name = "it_shelf")
@Filter(name = SiteScopedEntity.FILTER)
public class ItShelf extends SiteScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, length = 100)
    public String label;

    @OneToMany(mappedBy = "shelf")
    @Filter(name = SiteScopedEntity.FILTER)
    public List<ItBook> books = new ArrayList<>();

    public static ItShelf labelled(String label) {
        ItShelf shelf = new ItShelf();
        shelf.label = label;
        return shelf;
    }
}

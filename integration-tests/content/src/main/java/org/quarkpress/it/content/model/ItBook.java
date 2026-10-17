package org.quarkpress.it.content.model;

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

@Entity
@Table(name = "it_book")
@Filter(name = SiteScopedEntity.FILTER)
public class ItBook extends SiteScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, length = 100)
    public String title;

    @ManyToOne(optional = false)
    @JoinColumn(name = "shelf_id", nullable = false)
    public ItShelf shelf;

    public static ItBook on(ItShelf shelf, String title) {
        ItBook book = new ItBook();
        book.shelf = shelf;
        book.title = title;
        return book;
    }
}

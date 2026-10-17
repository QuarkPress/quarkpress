package org.quarkpress.it.tenancy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;

@Entity
@Table(name = "spike_note")
@Filter(name = SiteScopedEntity.FILTER)
public class SpikeNote extends SiteScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, length = 200)
    public String body;

    public static SpikeNote of(String body) {
        SpikeNote note = new SpikeNote();
        note.body = body;
        return note;
    }

    public static SpikeNote forSite(String tenantId, String body) {
        SpikeNote note = of(body);
        note.siteId = Long.valueOf(tenantId);
        return note;
    }
}

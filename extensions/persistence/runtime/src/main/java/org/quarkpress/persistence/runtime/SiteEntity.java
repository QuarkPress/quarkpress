package org.quarkpress.persistence.runtime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.SiteInfo;

@Entity
@Table(name = "qp_site")
public class SiteEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "domain", nullable = false, length = 200)
    public String domain;

    @Column(name = "path", nullable = false, length = 100)
    public String path;

    @Column(name = "name", nullable = false, length = 255)
    public String name;

    @Column(name = "public_flag", nullable = false)
    public short publicFlag;

    @Column(name = "archived", nullable = false)
    public boolean archived;

    @Column(name = "mature", nullable = false)
    public boolean mature;

    @Column(name = "spam", nullable = false)
    public boolean spam;

    @Column(name = "deleted", nullable = false)
    public boolean deleted;

    @Column(name = "registered_at", nullable = false)
    public Instant registeredAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    public int version;

    public static SiteEntity create(String domain, String path, String name, Instant now) {
        SiteEntity site = new SiteEntity();
        site.domain = domain;
        site.path = path;
        site.name = name;
        site.publicFlag = 1;
        site.registeredAt = now;
        site.updatedAt = now;
        return site;
    }

    public SiteInfo toInfo() {
        return new SiteInfo(SiteId.of(id), domain, path, name, publicFlag, archived, mature, spam, deleted);
    }
}

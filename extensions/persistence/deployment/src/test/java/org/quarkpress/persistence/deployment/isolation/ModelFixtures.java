package org.quarkpress.persistence.deployment.isolation;

import jakarta.persistence.Cacheable;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.NamedNativeQuery;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.OneToMany;
import java.util.List;
import java.util.Map;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.Formula;
import org.hibernate.annotations.SQLDelete;
import org.quarkpress.persistence.SiteScopedNativeSql;
import org.quarkpress.persistence.runtime.SiteScopedEntity;

/** Mappings the model checks read from a Jandex index; they are never part of a test application. */
public final class ModelFixtures {

    private ModelFixtures() {}

    @Entity
    @Filter(name = SiteScopedEntity.FILTER)
    public static class Book extends SiteScopedEntity {
        @Id
        Long id;
    }

    @Entity
    @Filter(name = SiteScopedEntity.FILTER)
    public static class Shelf extends SiteScopedEntity {
        @Id
        Long id;

        @SuppressWarnings("rawtypes")
        @OneToMany(targetEntity = Book.class)
        List raw;

        @OneToMany
        Book[] array;

        @ManyToMany
        List<? extends Book> wildcard;

        @OneToMany
        Map<String, Book> byName;

        @OneToMany
        @Filter(name = SiteScopedEntity.FILTER)
        List<Book> filtered;

        private List<Book> property;

        @OneToMany
        public List<Book> getProperty() {
            return property;
        }
    }

    @Entity
    @Filter(name = SiteScopedEntity.FILTER)
    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
    public static class Root extends SiteScopedEntity {
        @Id
        Long id;
    }

    @Entity
    public static class Child extends Root {}

    @MappedSuperclass
    @Cacheable
    public abstract static class CachedBase extends SiteScopedEntity {}

    @Entity
    @Filter(name = SiteScopedEntity.FILTER)
    public static class ViaCachedBase extends CachedBase {
        @Id
        Long id;
    }

    @NamedNativeQuery(name = "all", query = "select * from qp_option")
    @NamedQuery(name = "move", query = "update OptionEntity o set o.siteId = :to")
    @NamedQuery(name = "copy", query = "insert into OptionEntity (name, autoload) values ('x', true)")
    @NamedQuery(name = "read", query = "from OptionEntity o where o.siteId = :site")
    public static class Queries {
        @Formula("(select count(*) from qp_option)")
        long count;
    }

    @SiteScopedNativeSql
    @NamedNativeQuery(name = "own", query = "select * from qp_option where site_id = ?1")
    @SQLDelete(sql = "delete from qp_option where id = ? and site_id = ?")
    public static class AllowedNative {}
}

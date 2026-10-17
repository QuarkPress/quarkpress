package org.quarkpress.persistence.deployment.isolation;

import io.quarkus.hibernate.orm.PersistenceUnit;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.StatelessSession;
import org.quarkpress.persistence.runtime.SiteScopedEntity;

public final class Attempts {

    private Attempts() {}

    @ApplicationScoped
    public static class NativeSql {
        @Inject
        @PersistenceUnit("quarkpress")
        EntityManager em;

        public long count() {
            return ((Number) em.createNativeQuery("select count(*) from qp_option")
                            .getSingleResult())
                    .longValue();
        }
    }

    @ApplicationScoped
    public static class HqlInsert {
        @Inject
        @PersistenceUnit("quarkpress")
        EntityManager em;

        public int copy() {
            return em.unwrap(Session.class)
                    .createMutationQuery("insert into OptionEntity (name, autoload) values ('x', true)")
                    .executeUpdate();
        }
    }

    @ApplicationScoped
    public static class SiteIdAssignment {
        @Inject
        @PersistenceUnit("quarkpress")
        EntityManager em;

        public int move() {
            return em.createQuery("update OptionEntity o set o.siteId = 2 where o.id = 1")
                    .executeUpdate();
        }
    }

    @ApplicationScoped
    public static class FilterOff {
        @Inject
        @PersistenceUnit("quarkpress")
        EntityManager em;

        public void off() {
            em.unwrap(Session.class).disableFilter(SiteScopedEntity.FILTER);
        }
    }

    @ApplicationScoped
    public static class StatelessOpener {
        @Inject
        @PersistenceUnit("quarkpress")
        EntityManager em;

        public void open() {
            em.getEntityManagerFactory()
                    .unwrap(SessionFactory.class)
                    .openStatelessSession()
                    .close();
        }
    }

    @ApplicationScoped
    public static class StatelessInjected {
        @Inject
        @PersistenceUnit("quarkpress")
        StatelessSession session;

        public boolean open() {
            return session.isOpen();
        }
    }

    @ApplicationScoped
    public static class StatelessLookup {
        @Inject
        @PersistenceUnit("quarkpress")
        Instance<StatelessSession> sessions;

        public boolean available() {
            return sessions.isResolvable();
        }
    }
}

package org.quarkpress.persistence.deployment.isolation;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaUpdate;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.StatelessSession;
import org.quarkpress.core.runtime.Binding;
import org.quarkpress.core.runtime.SiteBinding;
import org.quarkpress.persistence.SiteScopedNativeSql;
import org.quarkpress.persistence.runtime.OptionEntity;
import org.quarkpress.persistence.runtime.OptionRepository;
import org.quarkpress.persistence.runtime.SiteScopedEntity;

public final class Fixtures {

    private Fixtures() {}

    public static class Unsafe {
        long count(EntityManager em) {
            return ((Number) em.createNativeQuery("select count(*) from qp_option")
                            .getSingleResult())
                    .longValue();
        }
    }

    public static class UpperCase {
        void run(EntityManager em) {
            em.createNativeQuery("DELETE FROM QP_OPTION WHERE ID = 1").executeUpdate();
        }
    }

    public static class Concatenated {
        void run(EntityManager em, long id) {
            em.createNativeQuery("delete from qp_option where id = " + id).executeUpdate();
        }
    }

    public static class Mutation {
        void run(Session session) {
            session.createNativeMutationQuery("update `qp_option` set autoload = false")
                    .executeUpdate();
        }
    }

    public static class NetworkTable {
        void run(EntityManager em) {
            em.createNativeQuery("select * from qp_site").getResultList();
        }
    }

    public static class SimilarName {
        void run(EntityManager em) {
            em.createNativeQuery("select * from qp_option_archive").getResultList();
        }
    }

    @SiteScopedNativeSql
    public static class Annotated {
        void run(EntityManager em, long site) {
            em.createNativeQuery("select * from qp_option where site_id = ?1")
                    .setParameter(1, site)
                    .getResultList();
        }
    }

    public static class TextOnly {
        String sql() {
            return "select * from qp_option";
        }
    }

    public static class HqlInsert {
        void run(Session session) {
            session.createMutationQuery("insert into OptionEntity (name, autoload) values ('x', true)")
                    .executeUpdate();
        }
    }

    public static class SiteIdSet {
        void run(Session session) {
            session.createMutationQuery("update OptionEntity o set o.autoload = true, o.siteId = 2 where o.id = 1")
                    .executeUpdate();
        }
    }

    public static class PanacheSiteIdUpdate {
        void run(OptionRepository options) {
            options.update("siteId = ?1 where id = ?2", 2L, 1L);
        }
    }

    public static class GuardedBulkUpdate {
        void run(OptionRepository options) {
            options.update("autoload = false where siteId = ?1 and name = ?2", 1L, "n");
        }
    }

    public static class FindBySite {
        void run(OptionRepository options) {
            options.find("siteId = ?1 and name = ?2", 1L, "n").firstResultOptional();
        }
    }

    /** A find string next to a call of an update method that takes no query. */
    public static class FindThenUpdateRow {
        void run(OptionRepository options) {
            update(options.find("siteId = ?1", 1L).firstResult());
        }

        void update(Object row) {}
    }

    public static class DisableFilter {
        void run(Session session) {
            session.disableFilter(SiteScopedEntity.FILTER);
        }
    }

    public static class DisableOtherFilter {
        void run(Session session) {
            session.disableFilter("other");
        }
    }

    public static class OpenStateless {
        void run(SessionFactory factory) {
            factory.openStatelessSession().close();
        }
    }

    /** Core and persistence may bind the network; this class is in a persistence package. */
    public static class NetworkInside {
        void run(SiteBinding binding) {
            binding.bind(Binding.NETWORK);
        }
    }

    // Paths found in review: each one must be reported.

    public static class Jdbc {
        void run(DataSource dataSource) throws SQLException {
            try (Connection connection = dataSource.getConnection()) {
                connection.prepareStatement("select * from qp_option").executeQuery();
            }
        }
    }

    public static class DoWork {
        void run(Session session) {
            session.doWork(connection -> connection.createStatement().executeUpdate("delete from qp_option"));
        }
    }

    public static class HelperMethod {
        String sql() {
            return "select * from qp_option";
        }

        void run(EntityManager em) {
            em.createNativeQuery(sql()).getResultList();
        }
    }

    public static class SplitLambda {
        Object run(EntityManager em, Supplier<Object> unused) {
            String sql = "select * from qp_option";
            Supplier<Object> query = () -> em.createNativeQuery(sql).getResultList();
            return query.get();
        }
    }

    public static class InsertWithoutInto {
        void run(Session session) {
            session.createMutationQuery("insert OptionEntity (name, autoload) values ('x', true)")
                    .executeUpdate();
        }
    }

    public static class SubqueryBeforeSiteId {
        void run(Session session) {
            session.createMutationQuery("update OptionEntity o set o.name = (select max(x.name) from OptionEntity x"
                            + " where x.id = 1), o.siteId = 2")
                    .executeUpdate();
        }
    }

    public static class FindAndUpdate {
        void run(OptionRepository options) {
            options.find("siteId = ?1 and name = ?2", 1L, "n").firstResult();
            options.update("autoload = false where siteId = ?1 and name = ?2", 1L, "n");
        }
    }

    public static class QueryInVariable {
        void run(OptionRepository options) {
            String query = "siteId = ?1 where id = ?2";
            options.update(query, 2L, 1L);
        }
    }

    public static class CriteriaSiteId {
        void run(EntityManager em) {
            CriteriaBuilder builder = em.getCriteriaBuilder();
            CriteriaUpdate<OptionEntity> update = builder.createCriteriaUpdate(OptionEntity.class);
            update.from(OptionEntity.class);
            update.set("siteId", 2L);
            em.createQuery(update).executeUpdate();
        }
    }

    public static class EnableFilter {
        void run(Session session) {
            session.enableFilter(SiteScopedEntity.FILTER).setParameter("siteId", -1L);
        }
    }

    public static class GetEnabledFilter {
        void run(Session session) {
            session.getEnabledFilter("qp_site").setParameter("siteId", 2L);
        }
    }

    public static class DisableThroughHelper {
        void off(Session session, String filter) {
            session.disableFilter(filter);
        }

        void run(Session session) {
            off(session, SiteScopedEntity.FILTER);
        }
    }

    public static class InStatelessTransaction {
        void run(SessionFactory factory) {
            factory.inStatelessTransaction(session -> session.insert(new Object()));
        }
    }

    public static class StatelessWithOptions {
        void run(Session session) {
            session.statelessWithOptions().open().close();
        }
    }

    public static class StatelessLookup {
        @Inject
        Instance<StatelessSession> sessions;

        boolean run() {
            return sessions.isResolvable();
        }
    }
}

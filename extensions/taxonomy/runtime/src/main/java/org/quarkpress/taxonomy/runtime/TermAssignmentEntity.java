package org.quarkpress.taxonomy.runtime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.TermId;
import org.quarkpress.persistence.runtime.SiteScopedEntity;

/** A post in a term (WordPress's term relationship), with the position WordPress's sorted taxonomies keep. */
@Entity
@Table(name = "qp_term_assignment")
@Filter(name = SiteScopedEntity.FILTER)
public class TermAssignmentEntity extends SiteScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "post_id", nullable = false)
    public long postId;

    @Column(name = "term_id", nullable = false)
    public long termId;

    @Column(name = "term_order", nullable = false)
    public int termOrder;

    public static TermAssignmentEntity create(SiteId site, PostId post, TermId term, int termOrder) {
        TermAssignmentEntity assignment = new TermAssignmentEntity();
        assignment.assignSite(site);
        assignment.postId = post.value();
        assignment.termId = term.value();
        assignment.termOrder = termOrder;
        return assignment;
    }
}

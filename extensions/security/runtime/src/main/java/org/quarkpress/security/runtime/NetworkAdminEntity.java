package org.quarkpress.security.runtime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** A network administrator (WordPress's super admin). Network data: no site filter applies. */
@Entity
@Table(name = "qp_network_admin")
public class NetworkAdminEntity {

    @Id
    @Column(name = "user_id")
    public Long userId;

    @Column(name = "granted_at", nullable = false)
    public Instant grantedAt;

    @Column(name = "granted_by")
    public Long grantedBy;
}

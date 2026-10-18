package org.quarkpress.persistence.runtime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The single row that writes of network data lock first. Network data: no site filter applies. */
@Entity
@Table(name = "qp_network_lock")
public class NetworkLockEntity {

    /** The id of the only row. */
    public static final Short ROW = 1;

    @Id
    @Column(name = "id")
    public Short id;
}

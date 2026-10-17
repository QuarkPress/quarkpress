package org.quarkpress.persistence.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "qp_network_option")
public class NetworkOptionEntity {

    @Id
    @Column(name = "name", length = 191)
    public String name;

    @JdbcTypeCode(SqlTypes.JSON)
    @JdbcType(QuarkPressJsonJdbcType.class)
    @Column(name = "value", nullable = false)
    public JsonNode value;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    public static NetworkOptionEntity create(String name, JsonNode value, Instant now) {
        NetworkOptionEntity option = new NetworkOptionEntity();
        option.name = StoredOptionNames.require(name);
        option.value = value;
        option.updatedAt = now;
        return option;
    }
}

package com.example.erp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "erp_invoice")
public class Invoice {

    @Id
    public Long id;

    @Column(name = "number", nullable = false, length = 40)
    public String number;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details")
    public InvoiceDetails details;
}

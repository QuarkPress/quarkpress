package com.example.erp;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class InvoiceRepository implements PanacheRepositoryBase<Invoice, Long> {}

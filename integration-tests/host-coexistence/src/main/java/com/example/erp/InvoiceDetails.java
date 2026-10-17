package com.example.erp;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** The host's own JSON document; its field names are written as the host's ObjectMapper names them. */
@RegisterForReflection
public class InvoiceDetails {

    public String customerName;
    public int itemCount;
}

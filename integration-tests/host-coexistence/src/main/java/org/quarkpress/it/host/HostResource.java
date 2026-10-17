package org.quarkpress.it.host;

import com.example.erp.Invoice;
import com.example.erp.InvoiceDetails;
import com.example.erp.InvoiceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.flyway.FlywayDataSource;
import io.quarkus.hibernate.orm.PersistenceUnit;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.flywaydb.core.Flyway;
import org.quarkpress.core.SiteContext;
import org.quarkpress.persistence.Options;
import org.quarkpress.persistence.QuarkPressPersistence;

@Path("/host")
public class HostResource {

    @Inject
    InvoiceRepository invoices;

    @Inject
    AgroalDataSource hostDataSource;

    @Inject
    @DataSource(QuarkPressPersistence.UNIT)
    AgroalDataSource quarkpressDataSource;

    @Inject
    EntityManagerFactory hostUnit;

    @Inject
    @PersistenceUnit(QuarkPressPersistence.UNIT)
    EntityManagerFactory quarkpressUnit;

    // Quarkus activates Flyway for the default datasource as soon as quarkus-flyway is on the class path.
    @Inject
    Flyway hostFlyway;

    @Inject
    @FlywayDataSource(QuarkPressPersistence.UNIT)
    Flyway quarkpressFlyway;

    @Inject
    Options options;

    @Inject
    SiteContext context;

    @POST
    @Path("/invoices/{id}/{number}")
    @Transactional
    public void createInvoice(@PathParam("id") long id, @PathParam("number") String number) {
        Invoice invoice = new Invoice();
        invoice.id = id;
        invoice.number = number;
        invoices.persist(invoice);
    }

    @GET
    @Path("/invoices/{id}")
    @Produces(MediaType.TEXT_PLAIN)
    public String invoice(@PathParam("id") long id) {
        return invoices.findById(id).number;
    }

    @PUT
    @Path("/invoices/{id}/details/{customer}/{items}")
    @Transactional
    public void setDetails(
            @PathParam("id") long id, @PathParam("customer") String customer, @PathParam("items") int items) {
        InvoiceDetails details = new InvoiceDetails();
        details.customerName = customer;
        details.itemCount = items;
        invoices.findById(id).details = details;
    }

    /** The host's JSON as the host's entity reads it back. */
    @GET
    @Path("/invoices/{id}/details")
    @Produces(MediaType.TEXT_PLAIN)
    @Transactional
    public String details(@PathParam("id") long id) {
        InvoiceDetails details = invoices.findById(id).details;
        return details.customerName + "/" + details.itemCount;
    }

    /** The JSON text stored in the host's column. */
    @GET
    @Path("/invoices/{id}/details-raw")
    @Produces(MediaType.TEXT_PLAIN)
    public String detailsRaw(@PathParam("id") long id) throws SQLException {
        try (Connection connection = hostDataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement("select details from erp_invoice where id = ?")) {
            statement.setLong(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    @PUT
    @Path("/qp-options/{name}")
    @Consumes(MediaType.APPLICATION_JSON)
    public void setOption(@PathParam("name") String name, JsonNode value) {
        options.set(name, value);
    }

    @GET
    @Path("/qp-options/{name}")
    @Produces(MediaType.TEXT_PLAIN)
    public Response option(@PathParam("name") String name) {
        return context.options()
                .get(name)
                .map(value -> Response.ok(value.asText()).build())
                .orElseGet(() -> Response.status(Response.Status.NOT_FOUND).build());
    }

    /** The option's JSON text as QuarkPress reads it back: object keys in the order they were written. */
    @GET
    @Path("/qp-options/{name}/json")
    @Produces(MediaType.TEXT_PLAIN)
    public Response optionJson(@PathParam("name") String name) {
        return context.options()
                .get(name)
                .map(value -> Response.ok(value.toString()).build())
                .orElseGet(() -> Response.status(Response.Status.NOT_FOUND).build());
    }

    @GET
    @Path("/check")
    @Produces(MediaType.APPLICATION_JSON)
    public CoexistenceReport check() throws SQLException {
        String history = "select script from %s where type = 'SQL' order by installed_rank";
        return new CoexistenceReport(
                strings(hostDataSource, history.formatted("flyway_schema_history")),
                strings(quarkpressDataSource, history.formatted("qp_schema_history")),
                entityNames(hostUnit),
                entityNames(quarkpressUnit),
                locations(hostFlyway),
                locations(quarkpressFlyway),
                databaseIdentity(hostDataSource).equals(databaseIdentity(quarkpressDataSource)));
    }

    private static List<String> locations(Flyway flyway) {
        return Arrays.stream(flyway.getConfiguration().getLocations())
                .map(Object::toString)
                .sorted()
                .toList();
    }

    private static List<String> strings(AgroalDataSource dataSource, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }

    private static List<String> entityNames(EntityManagerFactory unit) {
        List<String> names = new ArrayList<>();
        for (EntityType<?> type : unit.getMetamodel().getEntities()) {
            names.add(type.getName());
        }
        names.sort(null);
        return names;
    }

    /**
     * The database a connection lands in, as the server reports it (not the configured URL): the database name and the
     * server's own identity, so two servers with equally named databases differ.
     */
    private static String databaseIdentity(AgroalDataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            String sql = product.toLowerCase(Locale.ROOT).contains("postgres")
                    ? "select current_database() || '@' || pg_postmaster_start_time()::text || ':'"
                            + " || coalesce(inet_server_port(), 0)::text"
                    : "select concat(database(), '@', @@server_uuid, ':', @@port)";
            try (Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery(sql)) {
                rows.next();
                return rows.getString(1);
            }
        }
    }
}

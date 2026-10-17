package org.quarkpress.it.tenancy;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class SpikeNoteRepository implements PanacheRepositoryBase<SpikeNote, Long> {}

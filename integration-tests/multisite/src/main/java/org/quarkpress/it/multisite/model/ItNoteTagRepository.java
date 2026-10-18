package org.quarkpress.it.multisite.model;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class ItNoteTagRepository implements PanacheRepositoryBase<ItNoteTag, Long> {}

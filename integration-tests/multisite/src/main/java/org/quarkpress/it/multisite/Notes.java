package org.quarkpress.it.multisite;

import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Optional;
import org.quarkpress.it.multisite.model.ItNote;
import org.quarkpress.it.multisite.model.ItNoteRepository;
import org.quarkpress.it.multisite.model.ItNoteTag;
import org.quarkpress.it.multisite.model.ItNoteTagRepository;

/** Notes of the current site. */
@ApplicationScoped
public class Notes {

    @Inject
    ItNoteRepository notes;

    @Inject
    ItNoteTagRepository tags;

    @Transactional
    public long add(String text) {
        ItNote note = ItNote.of(text);
        notes.persist(note);
        return note.id;
    }

    /** Lists with no site predicate of its own: the site comes only from the always-on site filter. */
    @Transactional
    public List<String> texts() {
        return notes.listAll(Sort.by("id")).stream().map(note -> note.text).toList();
    }

    /** Finds by key, which the site filter also covers, so another site's note is not found. */
    @Transactional
    public Optional<String> textOf(long id) {
        return notes.findByIdOptional(id).map(note -> note.text);
    }

    /** Tags the note with that id, which need not belong to the current site: the database decides. */
    @Transactional
    public void tag(long noteId, String label) {
        tags.persist(ItNoteTag.on(notes.getEntityManager().getReference(ItNote.class, noteId), label));
        tags.flush();
    }
}

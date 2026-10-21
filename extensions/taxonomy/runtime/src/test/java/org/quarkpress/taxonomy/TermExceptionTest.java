package org.quarkpress.taxonomy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TermExceptionTest {

    @Test
    void insertAndUpdateRefusalsCarryTheirOwnCodesAndMessages() {
        assertThat(TermException.dbError("x"))
                .hasMessage("Could not insert term into the database.")
                .extracting(TermException::code)
                .isEqualTo("db_insert_error");
        assertThat(TermException.dbUpdateError("x"))
                .hasMessage("Could not update term in the database.")
                .extracting(TermException::code)
                .isEqualTo("db_update_error");
    }
}

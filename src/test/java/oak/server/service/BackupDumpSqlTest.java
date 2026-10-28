package oak.server.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQL text generation of the backup dump — the restore script's building blocks must be
 * exactly shaped (the restore runner executes them verbatim on a fresh Postgres).
 */
class BackupDumpSqlTest {

    @Test
    void sqlValueQuotesByRuntimeClass() {
        assertEquals("NULL", BackupDumpSql.sqlValue(null));
        assertEquals("42", BackupDumpSql.sqlValue(42L));
        assertEquals("-7", BackupDumpSql.sqlValue(-7));
        assertEquals("3.5", BackupDumpSql.sqlValue(3.5d));
        assertEquals("TRUE", BackupDumpSql.sqlValue(Boolean.TRUE));
        assertEquals("FALSE", BackupDumpSql.sqlValue(Boolean.FALSE));
        assertEquals("'hello'", BackupDumpSql.sqlValue("hello"));
        assertEquals("'it''s quoted'", BackupDumpSql.sqlValue("it's quoted"));
        // Timestamp-ish types are quoted by their toString form — Postgres parses all of these.
        assertEquals("'2026-10-28'", BackupDumpSql.sqlValue(LocalDate.of(2026, 10, 28)));
        assertTrue(BackupDumpSql.sqlValue(LocalDateTime.of(2026, 10, 28, 12, 30)).startsWith("'2026-10-28"));
        // The schema has no blob columns (all media live in MinIO) — meeting one means drift.
        assertThrows(IllegalStateException.class, () -> BackupDumpSql.sqlValue(new byte[1]));
    }

    @Test
    void statementsAreWellFormed() {
        assertEquals("SET session_replication_role = replica;\n", BackupDumpSql.replicationRolePreamble());
        assertEquals("SET session_replication_role = DEFAULT;\n", BackupDumpSql.replicationRoleEpilogue());
        assertEquals("TRUNCATE TABLE users, roles RESTART IDENTITY CASCADE;\n",
                BackupDumpSql.truncateStatement(List.of("users", "roles")));
        assertEquals("INSERT INTO users (id, nickname) VALUES (1, 'Max');\n",
                BackupDumpSql.insertStatement("users", List.of("id", "nickname"), List.of(1L, "Max")));
        assertEquals("INSERT INTO roles (id, name) VALUES (1, NULL);\n",
                BackupDumpSql.insertStatement("roles", List.of("id", "name"), java.util.Arrays.asList(1L, null)));
        assertEquals("SELECT setval('users_id_seq', COALESCE((SELECT MAX(id) FROM users), 0) + 1, false);\n",
                BackupDumpSql.setvalStatement("users"));
    }

    @Test
    void statementSplitterIsStringLiteralAware() throws Exception {
        // A ';' and newlines INSIDE a literal never end a statement; '' is an escaped quote.
        String script = "-- header comment\n"
                + "SET session_replication_role = replica;\n"
                + "INSERT INTO messages (content) VALUES ('line one;\nstill the same; value');\n"
                + "INSERT INTO messages (content) VALUES ('it''s; fine');\n"
                + "-- trailing comment without a newline at EOF";
        java.util.List<String> statements = new java.util.ArrayList<>();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.StringReader(script))) {
            BackupDumpSql.splitSqlStatements(reader, statements::add);
        }
        assertEquals(3, statements.size(), "statements: " + statements);
        assertTrue(statements.get(0).startsWith("SET session_replication_role"));
        assertTrue(statements.get(1).contains("line one;\nstill the same; value"));
        assertTrue(statements.get(2).contains("it''s; fine"));

        // A comment ending in a semicolon does not terminate anything.
        java.util.List<String> withComment = new java.util.ArrayList<>();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.StringReader("INSERT INTO roles (id) VALUES (1); -- done; really;\nSELECT 1;"))) {
            BackupDumpSql.splitSqlStatements(reader, withComment::add);
        }
        assertEquals(2, withComment.size(), "statements: " + withComment);
    }
}

package oak.server.service;

import java.util.List;

/**
 * SQL text generation for the backup dump — pure functions, unit-tested in isolation from JDBC.
 * The dump is a SELF-SUFFICIENT restore script: {@code SET session_replication_role = replica}
 * (Postgres-only — disables FK/trigger enforcement so the insert order does not matter),
 * one {@code TRUNCATE ... RESTART IDENTITY CASCADE} over every backed-up table, then
 * {@code INSERT} rows and {@code setval} every table's {@code <table>_id_seq} sequence back to
 * {@code max(id)} (the {@code RESTART IDENTITY} of the truncate already reset them to 1).
 *
 * <p>Schema (DDL) is deliberately NOT dumped: restore runs on a server of the SAME Flyway
 * version (the manifest carries it), so Flyway has already created the exact schema the rows
 * were read from — {@code ddl-auto: validate} is the drift alarm on every boot.
 */
public final class BackupDumpSql {

    private BackupDumpSql() {}

    /** One literal value, SQL-quoted by its runtime class. Numbers and booleans stay bare;
     *  everything else (strings, timestamps, JSON text columns) is single-quoted with the
     *  quote character doubled. {@code byte[]} is refused loudly — the schema has no blob
     *  columns (all media live in MinIO), so meeting one means the schema drifted. */
    public static String sqlValue(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Number) {
            return value.toString();
        }
        if (value instanceof Boolean bool) {
            return bool ? "TRUE" : "FALSE";
        }
        if (value instanceof byte[]) {
            throw new IllegalStateException("unexpected byte[] column — the schema has no blobs");
        }
        return quote(value.toString());
    }

    static String quote(String text) {
        return "'" + text.replace("'", "''") + "'";
    }

    public static String replicationRolePreamble() {
        return "SET session_replication_role = replica;\n";
    }

    public static String replicationRoleEpilogue() {
        return "SET session_replication_role = DEFAULT;\n";
    }

    public static String truncateStatement(List<String> tables) {
        return "TRUNCATE TABLE " + String.join(", ", tables) + " RESTART IDENTITY CASCADE;\n";
    }

    public static String insertStatement(String table, List<String> columns, List<?> values) {
        return "INSERT INTO " + table
                + " (" + String.join(", ", columns) + ") VALUES ("
                + joinValues(values) + ");\n";
    }

    /** Re-syncs the table's identity sequence to {@code max(id)} (0 for an emptied table with
     *  {@code false} so the next id is exactly 1). Only called for tables that HAVE an
     *  {@code id} column; the {@code <table>_id_seq} naming is the V1-migration convention. */
    public static String setvalStatement(String table) {
        return "SELECT setval('" + table + "_id_seq', COALESCE((SELECT MAX(id) FROM " + table
                + "), 0) + 1, false);\n";
    }

    private static String joinValues(List<?> values) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(sqlValue(values.get(index)));
        }
        return builder.toString();
    }

    /**
     * Streaming statement splitter for the restore leg: reads the decompressed script
     * character by character (the dump may be hundreds of megabytes — never fully in memory)
     * and hands each complete statement to {@code sink}. String-literal aware: a {@code ;}
     * or a newline INSIDE a single-quoted literal never ends a statement (multi-line message
     * contents contain both); {@code ''} inside a literal is an escaped quote; line comments
     * ({@code --}) run to the end of their line.
     */
    public static void splitSqlStatements(java.io.BufferedReader reader,
                                          java.util.function.Consumer<String> sink) throws java.io.IOException {
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        int pending = -1;
        while (true) {
            int read = pending != -1 ? pending : reader.read();
            pending = -1;
            if (read == -1) {
                break;
            }
            char c = (char) read;
            if (inString) {
                current.append(c);
                if (c == '\'') {
                    int next = reader.read();
                    if (next == '\'') {
                        current.append('\''); // escaped quote — still inside the literal
                    } else {
                        inString = false;
                        pending = next; // unread (push back unless EOF)
                    }
                }
                continue;
            }
            if (c == '\'') {
                inString = true;
                current.append(c);
                continue;
            }
            if (c == '-') {
                int next = reader.read();
                if (next == '-') {
                    // Line comment — swallow it WHOLE (to the newline): the dump keeps comments
                    // only as section headers between statements, never inside one.
                    int comment;
                    do {
                        comment = reader.read();
                    } while (comment != -1 && comment != '\n');
                } else {
                    current.append(c);
                    pending = next;
                }
                continue;
            }
            if (c == ';') {
                String statement = current.toString().trim();
                if (!statement.isEmpty()) {
                    sink.accept(statement);
                }
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        String tail = current.toString().trim();
        if (!tail.isEmpty()) {
            sink.accept(tail);
        }
    }
}

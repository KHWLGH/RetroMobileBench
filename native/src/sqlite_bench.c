#include "sqlite_bench.h"
#include <sqlite3.h>
#include <errno.h>
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#if SQLITE_VERSION_NUMBER != 3053001
#error "sqlite-tps-v1 requires SQLite 3.53.1"
#endif
#define PAYLOAD_BYTES 1024
static int cancelled;
void rmb_sqlite_reset_cancel(void) { __atomic_store_n(&cancelled, 0, __ATOMIC_RELAXED); }
void rmb_sqlite_cancel(void) { __atomic_store_n(&cancelled, 1, __ATOMIC_RELAXED); }
static int is_cancelled(void) { return __atomic_load_n(&cancelled, __ATOMIC_RELAXED); }
static double seconds(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) return -1;
    return (double) ts.tv_sec + (double) ts.tv_nsec / 1e9;
}

static int remove_files(const char *path) {
    const char *suffixes[] = {"-journal", "-wal", "-shm", ""};
    char name[PATH_MAX];
    int problem = 0;
    for (int i = 0; i < 4; i++) {
        if (snprintf(name, sizeof name, "%s%s", path, suffixes[i]) >= (int) sizeof name) return ENAMETOOLONG;
        if (unlink(name) != 0 && errno != ENOENT && !problem) problem = errno;
    }
    return problem;
}

static int scalar_equals(sqlite3 *db, const char *sql, const char *expected) {
    sqlite3_stmt *stmt = NULL;
    int code = sqlite3_prepare_v2(db, sql, -1, &stmt, NULL);
    if (code == SQLITE_OK) {
        code = sqlite3_step(stmt);
        if (code == SQLITE_ROW) {
            const unsigned char *value = sqlite3_column_text(stmt, 0);
            code = value && strcmp((const char *) value, expected) == 0 ? SQLITE_OK : SQLITE_MISMATCH;
        }
    }
    sqlite3_finalize(stmt);
    return code;
}

static int validate_rows(sqlite3 *db, int count, const unsigned char *payload) {
    sqlite3_stmt *stmt = NULL;
    int code = sqlite3_prepare_v2(db, "SELECT id,payload FROM bench ORDER BY id", -1, &stmt, NULL);
    if (code != SQLITE_OK) return code;
    int rows = 0;
    while ((code = sqlite3_step(stmt)) == SQLITE_ROW) {
        if (is_cancelled()) { code = SQLITE_INTERRUPT; break; }
        const void *blob = sqlite3_column_blob(stmt, 1);
        if (rows >= count || sqlite3_column_int(stmt, 0) != rows || !blob
                || sqlite3_column_bytes(stmt, 1) != PAYLOAD_BYTES
                || memcmp(blob, payload, PAYLOAD_BYTES) != 0) { code = SQLITE_CORRUPT; break; }
        rows++;
    }
    if (code == SQLITE_DONE) code = rows == count ? SQLITE_OK : SQLITE_CORRUPT;
    sqlite3_finalize(stmt);
    return code;
}

void rmb_sqlite_run(const char *directory, int transactions, char *json, size_t capacity) {
    sqlite3 *db = NULL;
    sqlite3_stmt *stmt = NULL;
    char path[PATH_MAX] = {0};
    int code = SQLITE_OK, cleanup_errno = 0, completed = 0;
    const char *stage = "parameters";
    int counts[3] = {0};
    double elapsed[3] = {0}, tps[3] = {0};
    unsigned char payload[2][PAYLOAD_BYTES];
    const char *names[] = {"insert", "update", "delete"};
    const char *sql[] = {"INSERT INTO bench(id,payload) VALUES(?1,?2)",
        "UPDATE bench SET payload=?2 WHERE id=?1", "DELETE FROM bench WHERE id=?1"};
    if (!directory || transactions < 1 || transactions > 3000) { code = SQLITE_MISUSE; goto finish; }
    if (snprintf(path, sizeof path, "%s/rmb-sqlite-bench.db", directory) >= (int) sizeof path - 9) {
        path[0] = 0;
        code = SQLITE_CANTOPEN;
        goto finish;
    }
    if (is_cancelled()) goto finish;
    stage = "cleanup_previous";
    cleanup_errno = remove_files(path);
    if (cleanup_errno) { code = SQLITE_IOERR_DELETE; goto finish; }
    stage = "open";
    code = sqlite3_open_v2(path, &db, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_NOMUTEX, NULL);
    if (code != SQLITE_OK) goto finish;
    sqlite3_extended_result_codes(db, 1);
    sqlite3_busy_timeout(db, 1000);
    stage = "configure";
    code = sqlite3_exec(db, "PRAGMA page_size=4096; PRAGMA cache_size=-2048; PRAGMA mmap_size=0;"
            "PRAGMA journal_mode=DELETE; PRAGMA synchronous=FULL; PRAGMA locking_mode=NORMAL;"
            "PRAGMA temp_store=MEMORY; PRAGMA auto_vacuum=NONE;"
            "CREATE TABLE bench(id INTEGER PRIMARY KEY,payload BLOB NOT NULL)", NULL, NULL, NULL);
    if (code != SQLITE_OK) goto finish;
    const char *checks[] = {"PRAGMA page_size", "PRAGMA cache_size", "PRAGMA journal_mode",
            "PRAGMA synchronous", "PRAGMA locking_mode", "PRAGMA temp_store"};
    const char *expected[] = {"4096", "-2048", "delete", "2", "normal", "2"};
    for (int i = 0; i < 6; i++) {
        code = scalar_equals(db, checks[i], expected[i]);
        if (code != SQLITE_OK) goto finish;
    }
    uint32_t seed = 0x524d4232U;
    for (int i = 0; i < PAYLOAD_BYTES; i++) {
        seed = seed * 1664525U + 1013904223U;
        payload[0][i] = (unsigned char) (seed >> 24);
        payload[1][i] = payload[0][i] ^ 0x5aU;
    }
    /* Warm-up commits exercise the same schema, then leave an empty table. */
    stage = "warmup";
    code = sqlite3_exec(db, "BEGIN IMMEDIATE; INSERT INTO bench VALUES(-1,zeroblob(1024)); COMMIT;"
            "BEGIN IMMEDIATE; DELETE FROM bench; COMMIT", NULL, NULL, NULL);
    if (code != SQLITE_OK) goto finish;
    for (int metric = 0; metric < 3; metric++) {
        stage = names[metric];
        if (is_cancelled()) break;
        code = sqlite3_prepare_v2(db, sql[metric], -1, &stmt, NULL);
        if (code != SQLITE_OK) break;
        double begin = seconds();
        if (begin < 0) { code = SQLITE_IOERR; break; }
        for (int i = 0; i < transactions; i++) {
            if (is_cancelled()) break;
            code = sqlite3_exec(db, "BEGIN IMMEDIATE", NULL, NULL, NULL);
            if (code != SQLITE_OK) break;
            code = sqlite3_bind_int(stmt, 1, i);
            if (code == SQLITE_OK && metric < 2)
                code = sqlite3_bind_blob(stmt, 2, payload[metric], PAYLOAD_BYTES, SQLITE_STATIC);
            if (code == SQLITE_OK) {
                code = sqlite3_step(stmt);
                if (code == SQLITE_DONE) code = sqlite3_changes(db) == 1 ? SQLITE_OK : SQLITE_CORRUPT;
            }
            int reset = sqlite3_reset(stmt);
            if (code == SQLITE_OK && reset != SQLITE_OK) code = reset;
            if (code == SQLITE_OK) code = sqlite3_exec(db, "COMMIT", NULL, NULL, NULL);
            if (code != SQLITE_OK) {
                sqlite3_exec(db, "ROLLBACK", NULL, NULL, NULL);
                break;
            }
            counts[metric]++;
        }
        double end = seconds();
        elapsed[metric] = end > begin ? end - begin : 0;
        sqlite3_finalize(stmt);
        stmt = NULL;
        if (code != SQLITE_OK || is_cancelled()) break;
        if (elapsed[metric] <= 0) { code = SQLITE_IOERR; break; }
        stage = "validate";
        code = validate_rows(db, metric == 2 ? 0 : transactions, payload[metric == 0 ? 0 : 1]);
        if (code != SQLITE_OK || is_cancelled()) break;
        tps[metric] = counts[metric] / elapsed[metric];
        completed++;
    }
    if (completed == 3 && !is_cancelled()) {
        stage = "integrity";
        code = scalar_equals(db, "PRAGMA quick_check", "ok");
        if (code == SQLITE_OK) stage = "done";
    }
finish:
    if (stmt) sqlite3_finalize(stmt);
    if (db) {
        sqlite3_exec(db, "ROLLBACK", NULL, NULL, NULL);
        int closed = sqlite3_close(db);
        if (code == SQLITE_OK && closed != SQLITE_OK) code = closed;
    }
    if (path[0]) {
        int removed = remove_files(path);
        if (!cleanup_errno) cleanup_errno = removed;
    }
    double composite_tps = completed == 3 ? (tps[0] + tps[1] + tps[2]) / 3.0 : 0.0;
    int total_metrics = completed == 3 ? 4 : completed;
    const char *status = is_cancelled() ? "cancelled"
            : code == SQLITE_OK && completed == 3 && !cleanup_errno ? "completed" : "failed";
    snprintf(json, capacity,
        "{\"test_version\":\"sqlite-tps-v1\",\"status\":\"%s\",\"error_stage\":\"%s\","
        "\"error_code\":%d,\"cleanup_errno\":%d,\"sqlite_version\":\"%s\",\"sqlite_source_id\":\"%s\","
        "\"transactions\":%d,\"rows_per_transaction\":1,\"payload_bytes\":1024,\"page_bytes\":4096,"
        "\"cache_kib\":2048,\"journal_mode\":\"DELETE\",\"synchronous\":\"FULL\","
        "\"completed_metrics\":%d,\"composite_tps\":%.6f,\"insert_tps\":%.6f,\"update_tps\":%.6f,\"delete_tps\":%.6f,"
        "\"completed_transactions\":[%d,%d,%d],\"elapsed_seconds\":[%.9f,%.9f,%.9f]}",
        status, stage, code, cleanup_errno, sqlite3_libversion(), sqlite3_sourceid(), transactions,
        total_metrics, composite_tps, tps[0], tps[1], tps[2], counts[0], counts[1], counts[2], elapsed[0], elapsed[1], elapsed[2]);
}

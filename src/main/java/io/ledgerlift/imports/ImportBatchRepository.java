package io.ledgerlift.imports;

import io.ledgerlift.template.GlInterfaceRow;
import io.ledgerlift.template.ParseError;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class ImportBatchRepository {

    private static final RowMapper<ImportBatch> BATCH = (rs, i) -> new ImportBatch(
            rs.getLong("id"), rs.getString("source_name"), rs.getString("checksum"),
            rs.getObject("ledger_id") == null ? null : rs.getLong("ledger_id"),
            BatchStatus.valueOf(rs.getString("status")),
            rs.getInt("row_count"), rs.getInt("parse_errors"), rs.getInt("error_count"),
            ts(rs.getTimestamp("created_at")), ts(rs.getTimestamp("updated_at")), ts(rs.getTimestamp("posted_at")));

    private static final RowMapper<ImportError> ERROR = (rs, i) -> new ImportError(
            rs.getLong("id"), rs.getLong("batch_id"),
            rs.getObject("interface_id") == null ? null : rs.getLong("interface_id"),
            rs.getObject("line_no") == null ? null : rs.getInt("line_no"),
            rs.getString("rule_code"), rs.getString("severity"), rs.getString("column_name"), rs.getString("message"));

    private static Instant ts(Timestamp t) { return t == null ? null : t.toInstant(); }

    private final JdbcTemplate jdbc;

    public ImportBatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ImportBatch> findByChecksum(String checksum) {
        return jdbc.query("select * from import_batches where checksum = ?", BATCH, checksum).stream().findFirst();
    }

    public Optional<ImportBatch> findById(long id) {
        return jdbc.query("select * from import_batches where id = ?", BATCH, id).stream().findFirst();
    }

    public List<ImportBatch> findAll() {
        return jdbc.query("select * from import_batches order by id desc", BATCH);
    }

    public long insertBatch(String sourceName, String checksum, BatchStatus status, int rowCount, int parseErrors) {
        KeyHolder kh = new GeneratedKeyHolder();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "insert into import_batches (source_name, checksum, status, row_count, parse_errors, error_count, created_at, updated_at)"
                            + " values (?,?,?,?,?,?,?,?)", new String[] {"id"});
            ps.setString(1, sourceName);
            ps.setString(2, checksum);
            ps.setString(3, status.name());
            ps.setInt(4, rowCount);
            ps.setInt(5, parseErrors);
            ps.setInt(6, parseErrors);
            ps.setTimestamp(7, now);
            ps.setTimestamp(8, now);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    /** Inserts staging rows in JDBC batches of 500. */
    public void insertInterfaceRows(long batchId, List<GlInterfaceRow> rows) {
        String sql = "insert into gl_interface (batch_id, line_no, ledger_name, period_name, journal_name, line_description,"
                + " account_code, currency_code, entered_dr, entered_cr, accounting_date, reference, raw_line)"
                + " values (?,?,?,?,?,?,?,?,?,?,?,?,?)";
        jdbc.batchUpdate(sql, rows, 500, (ps, r) -> {
            ps.setLong(1, batchId);
            ps.setInt(2, r.lineNo());
            ps.setString(3, r.ledgerName());
            ps.setString(4, r.periodName());
            ps.setString(5, r.journalName());
            ps.setString(6, r.lineDescription());
            ps.setString(7, r.accountCode());
            ps.setString(8, r.currencyCode());
            ps.setBigDecimal(9, r.enteredDr());
            ps.setBigDecimal(10, r.enteredCr());
            ps.setObject(11, r.accountingDate() == null ? null : Date.valueOf(r.accountingDate()));
            ps.setString(12, r.reference());
            ps.setString(13, r.rawLine().length() > 2000 ? r.rawLine().substring(0, 2000) : r.rawLine());
        });
    }

    public void insertParseErrors(long batchId, List<ParseError> errors) {
        String sql = "insert into import_errors (batch_id, interface_id, line_no, rule_code, severity, column_name, message)"
                + " select ?, gi.id, ?, 'PARSE', 'ERROR', ?, ? from (select 1) one"
                + " left join gl_interface gi on gi.batch_id = ? and gi.line_no = ?";
        jdbc.batchUpdate(sql, errors, 500, (ps, e) -> {
            ps.setLong(1, batchId);
            ps.setInt(2, e.lineNo());
            ps.setString(3, e.column());
            ps.setString(4, e.message());
            ps.setLong(5, batchId);
            ps.setInt(6, e.lineNo());
        });
    }

    public void insertError(long batchId, Long interfaceId, Integer lineNo, String ruleCode, String severity,
                            String column, String message) {
        jdbc.update("insert into import_errors (batch_id, interface_id, line_no, rule_code, severity, column_name, message)"
                + " values (?,?,?,?,?,?,?)", batchId, interfaceId, lineNo, ruleCode, severity, column, message);
    }

    public List<ImportError> errors(long batchId) {
        return jdbc.query("select * from import_errors where batch_id = ? order by line_no nulls first, id", ERROR, batchId);
    }

    public void deleteNonParseErrors(long batchId) {
        jdbc.update("delete from import_errors where batch_id = ? and rule_code <> 'PARSE'", batchId);
    }

    public int countErrors(long batchId) {
        return jdbc.queryForObject("select count(*) from import_errors where batch_id = ? and severity = 'ERROR'", Integer.class, batchId);
    }

    public void updateStatus(long batchId, BatchStatus status, int errorCount, Long ledgerId) {
        jdbc.update("update import_batches set status = ?, error_count = ?, ledger_id = coalesce(?, ledger_id), updated_at = ? where id = ?",
                status.name(), errorCount, ledgerId, Timestamp.from(Instant.now()), batchId);
    }

    public void markPosted(long batchId) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("update import_batches set status = 'POSTED', posted_at = ?, updated_at = ? where id = ?", now, now, batchId);
    }
}

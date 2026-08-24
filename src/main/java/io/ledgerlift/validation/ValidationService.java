package io.ledgerlift.validation;

import io.ledgerlift.imports.BatchNotFoundException;
import io.ledgerlift.imports.BatchStatus;
import io.ledgerlift.imports.ImportBatch;
import io.ledgerlift.imports.ImportBatchRepository;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ValidationService {

    private static final Logger log = LoggerFactory.getLogger(ValidationService.class);

    private final ImportBatchRepository repo;
    private final List<ValidationRule> rules;
    private final JdbcTemplate jdbc;

    public ValidationService(ImportBatchRepository repo, List<ValidationRule> rules, JdbcTemplate jdbc) {
        this.repo = repo;
        this.rules = rules;
        this.jdbc = jdbc;
    }

    public record ValidationSummary(ImportBatch batch, int errors, int warnings, List<Finding> findings) {}

    /**
     * Re-runnable: previous business findings are discarded, parse errors are kept.
     * A batch with zero ERROR findings (parse or business) becomes VALIDATED; otherwise REJECTED.
     */
    @Transactional
    public ValidationSummary validate(long batchId) {
        ImportBatch batch = repo.findById(batchId).orElseThrow(() -> new BatchNotFoundException(batchId));
        if (batch.status() == BatchStatus.POSTED) {
            throw new IllegalStateException("batch " + batchId + " is already POSTED");
        }
        if (batch.rowCount() == 0) {
            throw new IllegalStateException("batch " + batchId + " has no staged rows (rejected at parse)");
        }
        repo.deleteNonParseErrors(batchId);
        List<Finding> all = new ArrayList<>();
        for (ValidationRule rule : rules) {
            List<Finding> f = rule.apply(batchId);
            all.addAll(f);
            for (Finding x : f) {
                repo.insertError(batchId, x.interfaceId(), x.lineNo(), x.ruleCode(), x.severity().name(), x.column(), x.message());
            }
        }
        int errors = repo.countErrors(batchId);
        int warnings = (int) all.stream().filter(f -> f.severity() == Finding.Severity.WARNING).count();
        Long ledgerId = resolveLedger(batchId);
        BatchStatus status = errors == 0 ? BatchStatus.VALIDATED : BatchStatus.REJECTED;
        repo.updateStatus(batchId, status, errors, ledgerId);
        log.info("batch {} -> {} ({} errors, {} warnings, {} rules)", batchId, status, errors, warnings, rules.size());
        return new ValidationSummary(repo.findById(batchId).orElseThrow(), errors, warnings, all);
    }

    private Long resolveLedger(long batchId) {
        Integer names = jdbc.queryForObject(
                "select count(distinct ledger_name) from gl_interface where batch_id = ? and ledger_name is not null", Integer.class, batchId);
        if (names == null || names != 1) return null;
        List<Long> ids = jdbc.queryForList(
                "select distinct l.id from gl_interface gi join ledgers l on l.name = gi.ledger_name where gi.batch_id = ?", Long.class, batchId);
        return ids.size() == 1 ? ids.get(0) : null;
    }
}

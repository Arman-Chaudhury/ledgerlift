package io.ledgerlift.imports;

import io.ledgerlift.template.ParsePolicy;
import io.ledgerlift.template.ParseResult;
import io.ledgerlift.template.TemplateParser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Upload -> parse -> stage. Idempotent on file checksum. */
@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    private final ImportBatchRepository repo;

    public ImportService(ImportBatchRepository repo) {
        this.repo = repo;
    }

    public record UploadOutcome(ImportBatch batch, boolean duplicate) {}

    @Transactional
    public UploadOutcome upload(byte[] bytes, String fileName, ParsePolicy policy) {
        String checksum = sha256(bytes);
        var existing = repo.findByChecksum(checksum);
        if (existing.isPresent()) {
            log.info("duplicate upload of {} -> batch {}", fileName, existing.get().id());
            return new UploadOutcome(existing.get(), true);
        }
        ParseResult parsed = new TemplateParser(policy).parse(bytes, fileName);
        if (parsed.rejected()) {
            long id = repo.insertBatch(fileName, checksum, BatchStatus.REJECTED, 0, parsed.errors().size());
            repo.insertParseErrors(id, parsed.errors());
            log.info("batch {} rejected at parse: {} errors ({})", id, parsed.errors().size(), fileName);
            return new UploadOutcome(repo.findById(id).orElseThrow(), false);
        }
        long id = repo.insertBatch(fileName, checksum, BatchStatus.LOADED, parsed.rows().size(), parsed.errors().size());
        repo.insertInterfaceRows(id, parsed.rows());
        if (parsed.hasErrors()) {
            repo.insertParseErrors(id, parsed.errors());
        }
        log.info("batch {} loaded: {} rows, {} parse errors ({})", id, parsed.rows().size(), parsed.errors().size(), fileName);
        return new UploadOutcome(repo.findById(id).orElseThrow(), false);
    }

    public ImportBatch get(long id) {
        return repo.findById(id).orElseThrow(() -> new BatchNotFoundException(id));
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256(String s) {
        return sha256(s.getBytes(StandardCharsets.UTF_8));
    }
}

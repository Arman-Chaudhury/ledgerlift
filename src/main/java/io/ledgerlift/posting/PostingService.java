package io.ledgerlift.posting;

import io.ledgerlift.imports.BatchNotFoundException;
import io.ledgerlift.imports.BatchStatus;
import io.ledgerlift.imports.ImportBatch;
import io.ledgerlift.imports.ImportBatchRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class PostingService {

    private static final Logger log = LoggerFactory.getLogger(PostingService.class);

    private final ImportBatchRepository repo;
    private final List<PostingEngine> engines;
    private final String defaultEngine;

    public PostingService(ImportBatchRepository repo, List<PostingEngine> engines,
                          @Value("${ledgerlift.posting.engine:java}") String defaultEngine) {
        this.repo = repo;
        this.engines = engines;
        this.defaultEngine = defaultEngine;
    }

    public record PostingResult(ImportBatch batch, String engine, int journals, int lines) {}

    public PostingResult post(long batchId) {
        return post(batchId, defaultEngine);
    }

    public PostingResult post(long batchId, String engineName) {
        ImportBatch batch = repo.findById(batchId).orElseThrow(() -> new BatchNotFoundException(batchId));
        if (batch.status() != BatchStatus.VALIDATED) {
            throw new IllegalStateException("batch " + batchId + " is " + batch.status() + "; only VALIDATED batches can be posted");
        }
        PostingEngine engine = engines.stream().filter(e -> e.name().equalsIgnoreCase(engineName)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown posting engine '" + engineName + "'"));
        engine.post(batchId);
        ImportBatch after = repo.findById(batchId).orElseThrow();
        int journals = repo.countJournals(batchId);
        int lines = repo.countLines(batchId);
        log.info("batch {} posted via {}: {} journals, {} lines", batchId, engine.name(), journals, lines);
        return new PostingResult(after, engine.name(), journals, lines);
    }
}

package io.ledgerlift.imports;

public class BatchNotFoundException extends RuntimeException {
    public BatchNotFoundException(long id) { super("import batch " + id + " not found"); }
}

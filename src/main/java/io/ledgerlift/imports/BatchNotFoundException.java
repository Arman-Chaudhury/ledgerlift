package io.ledgerlift.imports;

@org.springframework.ws.soap.server.endpoint.annotation.SoapFault(faultCode = org.springframework.ws.soap.server.endpoint.annotation.FaultCode.CLIENT)
public class BatchNotFoundException extends RuntimeException {
    public BatchNotFoundException(long id) { super("import batch " + id + " not found"); }
}

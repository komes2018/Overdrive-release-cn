package com.ts.avm;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class IAvmServiceInterfaceContractTest {
    @Test
    public void matchesVendorTransactionPrefix() {
        assertEquals(1, IAvmServiceInterface.Stub.TRANSACTION_getAvmStatus);
        assertEquals(4, IAvmServiceInterface.Stub.TRANSACTION_startAvm);
        assertEquals(5, IAvmServiceInterface.Stub.TRANSACTION_stopAvm);
    }
}

package com.ts.avm;

import com.ts.avm.IAvmServiceListener;

interface IAvmServiceInterface {
    int getAvmStatus();
    int registerAvmStatusListener(IAvmServiceListener listener);
    int unregisterAvmStatusListener(IAvmServiceListener listener);
    void startAvm();
    void stopAvm();
}

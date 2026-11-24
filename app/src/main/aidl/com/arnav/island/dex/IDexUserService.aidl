package com.arnav.island.dex;

import com.arnav.island.dex.IDexUserCallback;

interface IDexUserService {
    void destroy() = 16777114;
    void setCallback(IDexUserCallback callback) = 1;
    boolean beginServerUpload() = 2;
    boolean writeServerChunk(in byte[] chunk) = 3;
    boolean finishServerUpload() = 4;
    void startSession(String displaySpec, String scidHex, String authToken) = 5;
    void stopSession() = 6;
    boolean isRunning() = 7;
    int getDisplayId() = 8;
}

package com.arnav.island.dex;

interface IDexUserCallback {
    void onStatus(String message);
    void onDisplayId(int displayId);
    void onSocketsReady(int videoPort, int controlPort);
    void onError(String message);
    void onEnded();
}

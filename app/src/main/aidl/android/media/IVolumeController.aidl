package android.media;

/**
 * {@hide}
 */
oneway interface IVolumeController {
    void displaySafeVolumeWarning(int flags);
    void volumeChanged(int streamType, int flags);
    void masterMuteChanged(int flags);
    void setLayoutDirection(int layoutDirection);
    void dismiss();
    void setA11yMode(int a11yMode);
    void displayCsdWarning(int csdWarning, int displayDurationMs);
}

package com.muoniumplayer.configskija.ui.notifications;

/** Optional extension for the host Painter to preserve source Medium (500) versus SemiBold (600). */
public interface NotificationTypography {
    void notificationText(String text, double x, double y, double size, int weight, int color);
    double notificationMeasure(String text, double size, int weight);
}

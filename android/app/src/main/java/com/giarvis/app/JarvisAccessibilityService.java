package com.giarvis.app;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/** Ponte opzionale per automazioni sullo schermo, attivabile solo dall'utente. */
public class JarvisAccessibilityService extends AccessibilityService {
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { /* eventi disponibili al router futuro */ }
    @Override public void onInterrupt() { }
}

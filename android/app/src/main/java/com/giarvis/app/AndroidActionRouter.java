package com.giarvis.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.provider.AlarmClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Azioni Android sicure e controlli hardware del dispositivo. */
public final class AndroidActionRouter {
    private static final String TAG = "JARVIS_ACTION";

    private AndroidActionRouter() {}

    public static boolean execute(Context context, String action, String value) {
        if (context == null || action == null) return false;
        Intent intent = null;
        String clean = value == null ? "" : value.trim();

        Log.d(TAG, "Executing action: " + action + " | Value: " + clean);

        switch (action.toLowerCase()) {
            case "open_url":
            case "search":
                intent = new Intent(Intent.ACTION_VIEW, Uri.parse(clean.startsWith("http") ? clean : "https://www.google.com/search?q=" + Uri.encode(clean)));
                break;

            case "spotify":
                try {
                    if (!clean.isEmpty()) {
                        intent = new Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(clean)));
                    } else {
                        intent = context.getPackageManager().getLaunchIntentForPackage("com.spotify.music");
                        if (intent == null) {
                            intent = new Intent(Intent.ACTION_VIEW, Uri.parse("spotify:"));
                        }
                    }
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                    return true;
                } catch (Exception fallback) {
                    intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/search/" + Uri.encode(clean)));
                }
                break;

            case "whatsapp":
                String phoneDigits = clean.replaceAll("[^0-9+]", "");
                if (!phoneDigits.isEmpty()) {
                    intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + phoneDigits.replace("+", "")));
                } else {
                    intent = context.getPackageManager().getLaunchIntentForPackage("com.whatsapp");
                    if (intent == null) {
                        intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://web.whatsapp.com"));
                    }
                }
                break;

            case "call":
                String numCall = clean.replaceAll("[^0-9+]", "");
                if (numCall.isEmpty() && !clean.isEmpty()) {
                    ContactResolver.ContactInfo info = ContactResolver.findContact(context, clean);
                    if (info != null && info.number != null && !info.number.isEmpty()) {
                        numCall = info.number;
                        Log.d(TAG, "Risolto contatto: " + info.name + " -> " + numCall);
                    }
                }
                if (!numCall.isEmpty()) {
                    if (context.checkSelfPermission(android.Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                        intent = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(numCall)));
                    } else {
                        intent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(numCall)));
                    }
                } else {
                    intent = new Intent(Intent.ACTION_DIAL);
                }
                break;

            case "dial":
                String numDial = clean.replaceAll("[^0-9+]", "");
                if (numDial.isEmpty() && !clean.isEmpty()) {
                    ContactResolver.ContactInfo dInfo = ContactResolver.findContact(context, clean);
                    if (dInfo != null && dInfo.number != null && !dInfo.number.isEmpty()) {
                        numDial = dInfo.number;
                    }
                }
                if (!numDial.isEmpty()) {
                    intent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(numDial)));
                } else {
                    intent = new Intent(Intent.ACTION_DIAL);
                }
                break;

            case "google_meet":
            case "meet":
                try {
                    String meetTarget = clean.trim();
                    if (!meetTarget.isEmpty()) {
                        ContactResolver.ContactInfo mInfo = ContactResolver.findContact(context, meetTarget);
                        String meetParam = (mInfo != null && mInfo.number != null) ? mInfo.number : meetTarget;
                        if (meetParam.contains("@") || meetParam.matches(".*[0-9+].*")) {
                            intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://meet.google.com/call/" + Uri.encode(meetParam)));
                        } else {
                            intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://meet.google.com/new"));
                        }
                    } else {
                        intent = context.getPackageManager().getLaunchIntentForPackage("com.google.android.apps.tachyon");
                        if (intent == null) {
                            intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://meet.google.com/new"));
                        }
                    }
                } catch (Exception e) {
                    intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://meet.google.com"));
                }
                break;

            case "map":
            case "maps":
                if (!clean.isEmpty()) {
                    intent = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(clean)));
                } else {
                    intent = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0"));
                }
                break;

            case "alarm":
                int hour = 7;
                int min = 0;
                String aClean = clean.toLowerCase();
                Matcher m = Pattern.compile("(\\d{1,2})[:.]?(\\d{2})?").matcher(clean);
                if (m.find()) {
                    try {
                        hour = Integer.parseInt(m.group(1));
                        if (m.group(2) != null) {
                            min = Integer.parseInt(m.group(2));
                        } else if (aClean.contains("mezza") || aClean.contains("trenta")) {
                            min = 30;
                        } else if (aClean.contains("quindici") || aClean.contains("un quarto")) {
                            min = 15;
                        } else if (aClean.contains("tre quarti") || aClean.contains("quarantacinque")) {
                            min = 45;
                        }
                    } catch (Exception ignored) {}
                }
                intent = new Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_MESSAGE, "Sveglia J.A.R.V.I.S.")
                        .putExtra(AlarmClock.EXTRA_HOUR, hour)
                        .putExtra(AlarmClock.EXTRA_MINUTES, min)
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, false);
                break;

            case "timer":
                int seconds = 60;
                String tClean = clean.toLowerCase();
                Matcher tm = Pattern.compile("(\\d+)").matcher(clean);
                if (tm.find()) {
                    try {
                        int val = Integer.parseInt(tm.group(1));
                        if (tClean.contains("ora") || tClean.contains("ore")) {
                            seconds = val * 3600;
                        } else if (tClean.contains("second")) {
                            seconds = val;
                        } else {
                            seconds = val * 60;
                        }
                    } catch (Exception ignored) {}
                } else if (tClean.contains("un minuto")) {
                    seconds = 60;
                } else if (tClean.contains("due minuti")) {
                    seconds = 120;
                } else if (tClean.contains("trenta secondi") || tClean.contains("mezzo minuto")) {
                    seconds = 30;
                }
                intent = new Intent(AlarmClock.ACTION_SET_TIMER)
                        .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                        .putExtra(AlarmClock.EXTRA_MESSAGE, "Timer J.A.R.V.I.S.")
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, false);
                break;

            case "torch":
            case "flashlight":
                try {
                    CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
                    if (cm != null) {
                        boolean enable = !clean.equalsIgnoreCase("off") && !clean.equalsIgnoreCase("spenta") && !clean.equalsIgnoreCase("spegni") && !clean.equalsIgnoreCase("false") && !clean.equalsIgnoreCase("0");
                        for (String id : cm.getCameraIdList()) {
                            try {
                                CameraCharacteristics chars = cm.getCameraCharacteristics(id);
                                Boolean hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                                if (Boolean.TRUE.equals(hasFlash) && (facing == null || facing == CameraCharacteristics.LENS_FACING_BACK)) {
                                    cm.setTorchMode(id, enable);
                                    Log.d(TAG, "Torch set to " + enable + " on camera " + id);
                                    return true;
                                }
                            } catch (Exception ignored) {}
                        }
                        for (String id : cm.getCameraIdList()) {
                            try {
                                CameraCharacteristics chars = cm.getCameraCharacteristics(id);
                                Boolean hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                                if (Boolean.TRUE.equals(hasFlash)) {
                                    cm.setTorchMode(id, enable);
                                    Log.d(TAG, "Torch set to " + enable + " on fallback camera " + id);
                                    return true;
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Torch error", e);
                }
                return false;

            case "volume":
                try {
                    AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                    if (am != null) {
                        String vClean = clean.toLowerCase();
                        if (vClean.contains("up") || vClean.contains("alza") || vClean.contains("piu") || vClean.contains("su")) {
                            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
                            return true;
                        } else if (vClean.contains("down") || vClean.contains("abbassa") || vClean.contains("meno") || vClean.contains("giu")) {
                            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
                            return true;
                        } else if (vClean.contains("max") || vClean.contains("massimo") || vClean.contains("100")) {
                            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                            am.setStreamVolume(AudioManager.STREAM_MUSIC, max, AudioManager.FLAG_SHOW_UI);
                            return true;
                        } else if (vClean.contains("mute") || vClean.contains("muto") || vClean.contains("zero") || vClean.contains("silenz")) {
                            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI);
                            return true;
                        } else {
                            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
                            return true;
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Volume control error", e);
                }
                return false;

            case "camera":
            case "fotocamera":
                intent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
                break;

            case "open_app":
                try {
                    PackageManager pm = context.getPackageManager();
                    String target = clean.toLowerCase();
                    String pkg = null;
                    if (target.contains("youtube")) pkg = "com.google.android.youtube";
                    else if (target.contains("calcolat") || target.contains("calc")) pkg = "com.google.android.calculator";
                    else if (target.contains("camera") || target.contains("fotocamera")) {
                        intent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
                        break;
                    }
                    else if (target.contains("chrome") || target.contains("browser")) pkg = "com.android.chrome";
                    else if (target.contains("telegram")) pkg = "org.telegram.messenger";
                    else if (target.contains("gmail") || target.contains("mail")) pkg = "com.google.android.gm";
                    else if (target.contains("foto") || target.contains("galleria") || target.contains("gallery")) pkg = "com.google.android.apps.photos";
                    else if (target.contains("maps") || target.contains("mappe")) pkg = "com.google.android.apps.maps";
                    else if (target.contains("play store") || target.contains("store")) pkg = "com.android.vending";
                    else if (target.contains("orologio") || target.contains("clock")) pkg = "com.google.android.deskclock";
                    else if (target.contains("contatti") || target.contains("rubrica")) pkg = "com.google.android.contacts";
                    else if (target.contains("calendario") || target.contains("calendar")) pkg = "com.google.android.calendar";
                    else if (target.contains("drive")) pkg = "com.google.android.apps.docs";

                    if (pkg != null) {
                        intent = pm.getLaunchIntentForPackage(pkg);
                    }
                    if (intent == null) {
                        List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
                        for (ApplicationInfo appInfo : apps) {
                            String label = pm.getApplicationLabel(appInfo).toString().toLowerCase();
                            if (label.contains(target) || target.contains(label)) {
                                intent = pm.getLaunchIntentForPackage(appInfo.packageName);
                                if (intent != null) break;
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Open app error", e);
                }
                break;

            case "settings":
                intent = new Intent(Settings.ACTION_SETTINGS);
                break;

            case "wifi_settings":
                intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
                break;

            case "bluetooth_settings":
                intent = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
                break;

            case "accessibility_settings":
                intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                break;

            case "notification_settings":
                intent = new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
                break;

            default:
                Log.w(TAG, "Unknown action: " + action);
                return false;
        }

        try {
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                Log.d(TAG, "Successfully started activity for action: " + action);
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start activity for action: " + action, e);
            return false;
        }
        return false;
    }
}

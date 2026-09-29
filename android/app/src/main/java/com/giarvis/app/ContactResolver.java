package com.giarvis.app;

import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.util.Log;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Risolutore intelligente per la ricerca dei contatti nella rubrica telefonica. */
public final class ContactResolver {
    private static final String TAG = "ContactResolver";

    public static class ContactInfo {
        public final String name;
        public final String number;
        public final int type;

        public ContactInfo(String name, String number, int type) {
            this.name = name;
            this.number = number;
            this.type = type;
        }

        @Override
        public String toString() {
            return name + " (" + number + ")";
        }
    }

    private ContactResolver() {}

    public static ContactInfo findContact(Context context, String query) {
        if (context == null || query == null || query.trim().isEmpty()) return null;
        if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Permesso READ_CONTACTS non accordato.");
            return null;
        }

        String raw = query.trim();
        String normalizedQuery = normalize(raw);
        String cleanQuery = normalizedQuery
                .replaceAll("(?i)\\b(a|al|alla|allo|ai|agli|alle|il|la|lo|i|gli|le|un|uno|una|mio|mia|miei|mie|mio padre|mia madre|per|su|di|da|con)\\b", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (cleanQuery.isEmpty()) cleanQuery = normalizedQuery;

        Uri uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI;
        String[] projection = new String[]{
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE
        };

        ContactInfo exactMatch = null;
        ContactInfo startsWithMatch = null;
        ContactInfo partialMatch = null;

        try (Cursor cursor = context.getContentResolver().query(uri, projection, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
                int numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);
                int typeIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE);

                do {
                    String name = cursor.getString(nameIdx);
                    String number = cursor.getString(numIdx);
                    int type = typeIdx >= 0 ? cursor.getInt(typeIdx) : ContactsContract.CommonDataKinds.Phone.TYPE_OTHER;

                    if (name != null && number != null) {
                        String cleanNum = cleanNumber(number);
                        if (cleanNum.isEmpty()) continue;

                        String normName = normalize(name);

                        // 1. Match perfetto
                        if (normName.equals(cleanQuery) || normName.equals(normalizedQuery)) {
                            ContactInfo match = new ContactInfo(name, cleanNum, type);
                            if (type == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE) {
                                return match; // Priorità assoluta al cellulare
                            }
                            if (exactMatch == null) exactMatch = match;
                        }

                        // 2. Il nome inizia con la query (es. "Marco" per "Marco Rossi")
                        if (normName.startsWith(cleanQuery + " ") || normName.startsWith(cleanQuery)) {
                            if (startsWithMatch == null || type == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE) {
                                startsWithMatch = new ContactInfo(name, cleanNum, type);
                            }
                        }

                        // 3. Match parziale
                        if (normName.contains(cleanQuery) || cleanQuery.contains(normName)) {
                            if (partialMatch == null || type == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE) {
                                partialMatch = new ContactInfo(name, cleanNum, type);
                            }
                        }
                    }
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            Log.e(TAG, "Errore durante la ricerca contatti in rubrica", e);
        }

        if (exactMatch != null) return exactMatch;
        if (startsWithMatch != null) return startsWithMatch;
        return partialMatch;
    }

    public static String cleanNumber(String num) {
        if (num == null) return "";
        return num.replaceAll("[^0-9+]", "");
    }

    public static String normalize(String str) {
        if (str == null) return "";
        String normalized = Normalizer.normalize(str, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .trim();
        return normalized;
    }
}

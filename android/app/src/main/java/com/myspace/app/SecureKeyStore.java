package com.myspace.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Stores the Gemini API key encrypted with an AES-256-GCM key that never leaves
 * the Android Keystore. The ciphertext lives in private SharedPreferences.
 */
final class SecureKeyStore {
    private static final String PREFS = "myspace_secure";
    private static final String ALIAS = "myspace_gemini_key_v1";
    private static final String ENTRY = "gemini_key_v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final SharedPreferences prefs;

    SecureKeyStore(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    synchronized void save(String apiKey) throws Exception {
        SecretKey key = getOrCreateKey();
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(apiKey.getBytes(StandardCharsets.UTF_8));
        byte[] payload = new byte[1 + iv.length + encrypted.length];
        payload[0] = (byte) iv.length;
        System.arraycopy(iv, 0, payload, 1, iv.length);
        System.arraycopy(encrypted, 0, payload, 1 + iv.length, encrypted.length);
        prefs.edit()
                .putString(ENTRY, Base64.encodeToString(payload, Base64.NO_WRAP))
                .commit();
    }

    synchronized String load() {
        String encoded = prefs.getString(ENTRY, null);
        if (encoded == null || encoded.isEmpty()) return "";
        try {
            byte[] payload = Base64.decode(encoded, Base64.NO_WRAP);
            if (payload.length < 2) return "";
            int ivLength = payload[0] & 0xff;
            if (ivLength < 12 || payload.length <= 1 + ivLength) return "";
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(),
                    new GCMParameterSpec(128, payload, 1, ivLength));
            byte[] plain = cipher.doFinal(payload, 1 + ivLength, payload.length - 1 - ivLength);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Keystore key was invalidated (restore/reset); forget the entry.
            prefs.edit().remove(ENTRY).apply();
            return "";
        }
    }

    synchronized boolean isConfigured() {
        return !load().isEmpty();
    }

    synchronized void clear() {
        prefs.edit().remove(ENTRY).apply();
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(ALIAS)) {
            return (SecretKey) keyStore.getKey(ALIAS, null);
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build());
        return generator.generateKey();
    }
}

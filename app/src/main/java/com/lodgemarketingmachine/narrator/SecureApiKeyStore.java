package com.lodgemarketingmachine.narrator;

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

final class SecureApiKeyStore {
    private static final String PREFS_NAME = "lodge_narrator_secure_preferences";
    private static final String PREF_DATA = "api_key_ciphertext";
    private static final String PREF_IV = "api_key_iv";
    private static final String KEYSTORE_ALIAS = "LodgeBookNarratorApiKey";

    private final SharedPreferences preferences;

    SecureApiKeyStore(Context context) {
        preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    void save(String apiKey) throws Exception {
        SecretKey secretKey = getOrCreateKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);
        byte[] encrypted = cipher.doFinal(apiKey.getBytes(StandardCharsets.UTF_8));
        preferences.edit()
                .putString(PREF_DATA, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(PREF_IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .apply();
    }

    String load() {
        String encryptedValue = preferences.getString(PREF_DATA, "");
        String ivValue = preferences.getString(PREF_IV, "");
        if (encryptedValue.isEmpty() || ivValue.isEmpty()) return "";
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            if (!keyStore.containsAlias(KEYSTORE_ALIAS)) return "";
            SecretKey secretKey = ((KeyStore.SecretKeyEntry) keyStore.getEntry(KEYSTORE_ALIAS, null)).getSecretKey();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey,
                    new GCMParameterSpec(128, Base64.decode(ivValue, Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(encryptedValue, Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            clear();
            return "";
        }
    }

    void clear() {
        preferences.edit().remove(PREF_DATA).remove(PREF_IV).apply();
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) keyStore.getEntry(KEYSTORE_ALIAS, null)).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyGenParameterSpec specification = new KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build();
        generator.init(specification);
        return generator.generateKey();
    }
}

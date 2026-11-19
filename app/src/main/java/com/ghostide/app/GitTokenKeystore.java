package com.ghostide.app;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.File;
import java.security.KeyStore;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * Mobile Cycle 2 (M4): the production key source for
 * GitTokenStore. The AES-256 key is generated inside the
 * Android Keystore under one alias and never leaves it;
 * encrypt and decrypt run through the Cipher API with the
 * key referenced, not extracted. Split from the store so
 * the store's crypto and file format stay testable on a
 * plain JVM with a software key.
 */
final class GitTokenKeystore {
    private static final String ALIAS = "ghostide.gittoken.v1";

    static GitTokenStore forApp(Context ctx) {
        return forFile(ctx, "git-tokens.dat");
    }

    /* Mobile Cycle 3 (M10): the pairing store. Same
       key, same crypto, its own file, so a desktop
       address can never collide with a git host key
       and unpairing touches nothing else. */
    static GitTokenStore forPairing(Context ctx) {
        return forFile(ctx, "pair-tokens.dat");
    }

    private static GitTokenStore forFile(Context ctx,
            String name) {
        return new GitTokenStore(
            new File(ctx.getFilesDir(), name),
            new GitTokenStore.KeySource() {
                @Override public SecretKey key()
                        throws Exception {
                    return keystoreKey();
                }
            });
    }

    private static SecretKey keystoreKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) {
            SecretKey k = (SecretKey) ks.getKey(ALIAS, null);
            if (k != null) return k;
        }
        KeyGenerator gen = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(ALIAS,
            KeyProperties.PURPOSE_ENCRYPT
                | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(
                KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build());
        return gen.generateKey();
    }
}

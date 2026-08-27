import java.io.*;
import java.security.*;
import java.util.*;
import com.android.apksig.ApkSigner;
import com.android.apksig.apk.MinSdkVersionException;

/**
 * Sign an APK with apksig, with configurable v1/v2/v3.
 * Usage: java -cp apksig.jar:bcpkix... SignApk <in.apk> <out.apk> <ks> <kspass> <alias> <keypass> <v1:true/false>
 */
public class SignApk {
    public static void main(String[] args) throws Exception {
        String in = args[0], out = args[1], ksPath = args[2], ksPass = args[3], alias = args[4], keyPass = args[5];
        boolean v1 = Boolean.parseBoolean(args[6]);
        KeyStore ks = KeyStore.getInstance("JKS");
        try (FileInputStream fis = new FileInputStream(ksPath)) {
            ks.load(fis, ksPass.toCharArray());
        }
        KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry) ks.getEntry(alias,
                new KeyStore.PasswordProtection(keyPass.toCharArray()));
        java.security.cert.X509Certificate[] chain = new java.security.cert.X509Certificate[entry.getCertificateChain().length];
        for (int i = 0; i < chain.length; i++) {
            chain[i] = (java.security.cert.X509Certificate) entry.getCertificateChain()[i];
        }
        ApkSigner.SignerConfig signerConfig = new ApkSigner.SignerConfig.Builder(
                "modder", entry.getPrivateKey(), Arrays.asList(chain)).build();
        ApkSigner.Builder builder = new ApkSigner.Builder(Collections.singletonList(signerConfig))
                .setInputApk(new File(in))
                .setOutputApk(new File(out))
                .setV1SigningEnabled(v1)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setDebuggableApkPermitted(true);
        try {
            builder.build().sign();
            System.out.println("SIGNED v1=" + v1 + " -> " + out);
        } catch (MinSdkVersionException e) {
            System.out.println("MinSdkVersionException: " + e.getMessage());
        }
    }
}

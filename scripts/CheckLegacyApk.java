import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipFile;

/** Host-side APK format checks. Run with the existing Android Studio JBR. */
class CheckLegacyApk {
    public static void main(String[] paths) throws Exception {
        if (paths.length == 0) throw new IllegalArgumentException("Pass APK paths");
        for (String path : paths) try (ZipFile zip = new ZipFile(path)) {
            Set<String> libraries = new HashSet<>();
            int dexFiles = 0, methods = 0;
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                String name = entry.getName();
                if (name.matches("classes[0-9]*\\.dex")) {
                    dexFiles++;
                    byte[] header;
                    try (var input = zip.getInputStream(entry)) { header = input.readNBytes(112); }
                    require(header.length == 112, "Truncated DEX: " + path);
                    require(new String(header, 0, 8, StandardCharsets.US_ASCII).equals("dex\n035\0"),
                            "DEX version must be 035 for Froyo: " + path);
                    methods += ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(88);
                }
                if (name.startsWith("lib/") && name.endsWith(".so")) {
                    libraries.add(name);
                    require(entry.getMethod() == java.util.zip.ZipEntry.DEFLATED,
                            "Old PackageManager requires extracted/compressed JNI libraries: " + name);
                }
            }
            require(dexFiles == 1 && methods > 0 && methods < 65536, "Expected one DEX under 64K methods: " + path);
            if (!path.contains("androidTest")) {
                require(libraries.equals(Set.of("lib/armeabi-v7a/librmb.so", "lib/armeabi-v7a/librmb_noneon.so", "lib/x86/librmb.so")),
                        "Unexpected APK ABI/library set: " + libraries);
                var notices = zip.getEntry("assets/benchmark-notices.txt");
                require(notices != null, "Missing native license notices");
                String text;
                try (var input = zip.getInputStream(notices)) { text = new String(input.readAllBytes(), StandardCharsets.UTF_8); }
                require(text.contains("GCC RUNTIME LIBRARY EXCEPTION") && text.contains("GNU GENERAL PUBLIC LICENSE"),
                        "Missing GNU runtime license and exception");
            }
            System.out.println("PASS " + path + ": one DEX 035, " + methods + " methods, libraries=" + libraries);
        }
    }
    private static void require(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}

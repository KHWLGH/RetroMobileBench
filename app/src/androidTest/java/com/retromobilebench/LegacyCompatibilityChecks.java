package com.retromobilebench;

import android.content.Context;
import java.io.StringReader;
import org.json.JSONArray;
import org.json.JSONObject;

/** Regression checks for API 8 fallbacks and score separation across toolchains. */
final class LegacyCompatibilityChecks {
    static void run(Context context) throws Exception {
        check(PlatformCompat.parseTotalMemory(new StringReader("MemTotal: 123456 kB\nMemFree: 1 kB\n")) == 126418944L, "meminfo KiB conversion");
        for (String value : new String[]{"", "MemFree: 42 kB", "MemTotal: -1 kB", "MemTotal: text kB", "MemTotal: 123 MB", "MemTotal: 9223372036854775807 kB"})
            check(PlatformCompat.parseTotalMemory(new StringReader(value)) == -1, "missing/malformed/overflow memory rejected");
        JSONObject device = DeviceSnapshot.collect(context);
        check(device.getString("native_build_id").equals(BuildConfig.NATIVE_BUILD_ID), "native build identity recorded");
        check(device.optLong("total_memory_bytes") > 0, "total memory collected");
        check(BuildConfig.NATIVE_BUILD_ID.startsWith("r10e-gcc49-"), "pinned legacy compiler group");
        check(NativeBridge.probeReport().contains("native build: " + BuildConfig.NATIVE_BUILD_ID),
                "packaged native binary matches the Java build identity");
        JSONObject skipped = GpuClient.parse("SKIP: ETC1 unsupported", "gl_fill_off");
        check("skipped".equals(skipped.getString("status")) && skipped.getInt("completed_metrics") == 0 && !skipped.has("score"), "unsupported GPU has no score");
        check("failed".equals(GpuClient.parse("ERROR: unexpected error", "gl_fill_off").getString("status")), "unexpected errors remain failures");
        JSONObject raw = new JSONObject().put("results", new JSONArray().put(new JSONObject()
                .put("status", "FAILED").put("error_string", "OUT_OF_VMEMORY")));
        check("skipped".equals(GpuClient.parse(raw.toString(), "gl_fill_off").getString("status")), "known GPU memory shortage skips");
        raw.getJSONArray("results").getJSONObject(0).put("error_string", "SHADER_ERROR");
        check("failed".equals(GpuClient.parse(raw.toString(), "gl_fill_off").getString("status")), "shader failures stay visible");
        NativeBridge.resetScimarkCancellation();
        JSONObject sample = new JSONObject(NativeBridge.runScimark(1, 50));
        sample.put("native_build_id", BuildConfig.NATIVE_BUILD_ID).put("native_library", NativeLoader.load())
                .put("native_abi", device.getString("abi"));
        JSONObject report = new JSONObject().put("suite_version", BenchmarkSuite.SCIMARK.version)
                .put("status", "completed").put("repetitions", 3).put("started_ms", 0);
        JSONArray samples = new JSONArray();
        for (int i = 0; i < 3; i++) samples.put(new JSONObject(sample.toString()));
        report.put("samples", samples);
        BenchmarkReport.summarize(report);
        check(report.has("median"), "same native build aggregates");
        samples.getJSONObject(2).put("native_abi", "other-abi");
        BenchmarkReport.summarize(report);
        check(!report.has("median"), "different ABIs never aggregate");
        samples.getJSONObject(2).put("native_abi", device.getString("abi"));
        samples.getJSONObject(2).put("native_build_id", "old-compiler");
        BenchmarkReport.summarize(report);
        check(!report.has("median"), "different native builds never aggregate");
        samples.getJSONObject(2).remove("native_build_id");
        BenchmarkReport.summarize(report);
        check(!report.has("median"), "legacy and new samples never aggregate");
        for (int i = 0; i < 3; i++) {
            samples.getJSONObject(i).remove("native_build_id");
            samples.getJSONObject(i).remove("native_library");
            samples.getJSONObject(i).remove("native_abi");
        }
        BenchmarkReport.summarize(report);
        check(report.has("median") && BenchmarkReport.format(report).contains("CPU"), "legacy records remain readable");
        verifyCpuSocResolver(context);
    }

    static void verifyCpuSocResolver(Context context) throws Exception {
        // 1. Samsung GT-I9260 with ro.chipname=OMAP4470 + Hardware=Superior
        JSONObject i9260 = new JSONObject()
                .put("model", "GT-I9260")
                .put("device", "superiorchn")
                .put("abi", "armeabi-v7a")
                .put("cpu0_max_frequency_khz", "1500000")
                .put("available_processors", 2)
                .put("cpu_identity", new JSONObject()
                        .put("Hardware", "Superior")
                        .put("CPU implementer", "0x41")
                        .put("CPU architecture", "7")
                        .put("CPU variant", "0x2")
                        .put("CPU part", "0xc09")
                        .put("CPU revision", "10"))
                .put("ui_extra", new JSONObject()
                        .put("sys_board_platform", "omap4")
                        .put("sys_product_board", "piranha")
                        .put("sys_chipname", "OMAP4470")
                        .put("gpu_display_name", "PowerVR SGX 544"));
        CpuSocResolver.Result r9260 = CpuSocResolver.resolve(i9260);
        check(r9260.matched && "Texas Instruments OMAP 4470".equals(r9260.socName), "GT-I9260 SoC: " + r9260.socName);
        check("ARM Cortex-A9".equals(r9260.cpuArch), "GT-I9260 uArch: " + r9260.cpuArch);

        // 2. Samsung GT-I9260 historical snapshot (only cpu_identity + device/clock)
        JSONObject i9260Hist = new JSONObject(i9260.toString());
        i9260Hist.remove("ui_extra");
        CpuSocResolver.Result r9260Hist = CpuSocResolver.resolve(i9260Hist);
        check("Texas Instruments OMAP 4470".equals(r9260Hist.socName), "GT-I9260 historical SoC: " + r9260Hist.socName);

        // 3. OMAP4430 vs OMAP4460 platform disambiguation
        JSONObject omap4430 = new JSONObject()
                .put("device", "espresso10wifi")
                .put("abi", "armeabi-v7a")
                .put("cpu0_max_frequency_khz", "1008000")
                .put("available_processors", 2)
                .put("cpu_identity", new JSONObject().put("CPU implementer", "0x41").put("CPU part", "0xc09"))
                .put("ui_extra", new JSONObject().put("sys_board_platform", "omap4").put("sys_product_board", "piranha"));
        check("Texas Instruments OMAP 4430".equals(CpuSocResolver.resolve(omap4430).socName), "OMAP4430 disambiguation");

        JSONObject omap4460 = new JSONObject()
                .put("device", "maguro")
                .put("abi", "armeabi-v7a")
                .put("cpu0_max_frequency_khz", "1200000")
                .put("available_processors", 2)
                .put("cpu_identity", new JSONObject().put("Hardware", "Tuna").put("CPU implementer", "0x41").put("CPU part", "0xc09"));
        check("Texas Instruments OMAP 4460".equals(CpuSocResolver.resolve(omap4460).socName), "OMAP4460 disambiguation");

        // 4. Xiaomi Mi 2A (QCT MSM8960 CDP + taurus + Adreno 320 + 1728 MHz -> MSM8960T Snapdragon S4 Pro)
        JSONObject mi2a = new JSONObject()
                .put("model", "MI 2A")
                .put("device", "taurus")
                .put("abi", "armeabi-v7a")
                .put("cpu0_max_frequency_khz", "1728000")
                .put("available_processors", 2)
                .put("cpu_identity", new JSONObject()
                        .put("Hardware", "QCT MSM8960 CDP")
                        .put("CPU implementer", "0x51")
                        .put("CPU part", "0x04d")
                        .put("Features", "swp half thumb fastmult vfp edsp neon vfpv3 tls vfpv4"))
                .put("ui_extra", new JSONObject().put("gpu_display_name", "Adreno (TM) 320"));
        CpuSocResolver.Result rMi2a = CpuSocResolver.resolve(mi2a);
        check("Qualcomm Snapdragon S4 Pro (MSM8960T)".equals(rMi2a.socName), "Mi 2A SoC: " + rMi2a.socName);
        check(rMi2a.cpuArch.contains("Qualcomm Krait"), "Mi 2A uArch: " + rMi2a.cpuArch);

        // 5. Quad-core misreported as MSM8960 -> APQ8064
        JSONObject apq8064 = new JSONObject()
                .put("abi", "armeabi-v7a")
                .put("available_processors", 4)
                .put("cpu_identity", new JSONObject().put("Hardware", "MSM8960").put("CPU implementer", "0x51").put("CPU part", "0x06f"));
        check("Qualcomm Snapdragon S4 Pro (APQ8064)".equals(CpuSocResolver.resolve(apq8064).socName), "APQ8064 4-core fixup");

        // 6. Exynos 4x12, MTK, Tegra 3, Allwinner, Rockchip, HiSilicon, Scorpion vs Cortex-A5
        JSONObject exynos4412 = new JSONObject().put("abi", "armeabi-v7a").put("available_processors", 4)
                .put("cpu_identity", new JSONObject().put("Hardware", "SMDK4x12").put("CPU implementer", "0x41").put("CPU part", "0xc09"));
        check("Samsung Exynos 4412".equals(CpuSocResolver.resolve(exynos4412).socName), "Exynos 4412");

        JSONObject mtk = new JSONObject().put("abi", "armeabi-v7a").put("available_processors", 4)
                .put("ui_extra", new JSONObject().put("sys_mediatek_platform", "MT6589"));
        check("MediaTek MT6589".equals(CpuSocResolver.resolve(mtk).socName), "MT6589");

        JSONObject tegra3 = new JSONObject().put("abi", "armeabi-v7a").put("available_processors", 4)
                .put("cpu_identity", new JSONObject().put("Hardware", "grouper"))
                .put("ui_extra", new JSONObject().put("sys_board_platform", "tegra3"));
        check("NVIDIA Tegra 3 (T30L)".equals(CpuSocResolver.resolve(tegra3).socName), "Tegra 3 grouper");

        JSONObject sun7i = new JSONObject().put("abi", "armeabi-v7a").put("available_processors", 2)
                .put("cpu_identity", new JSONObject().put("Hardware", "sun7i"));
        check("Allwinner A20".equals(CpuSocResolver.resolve(sun7i).socName), "Allwinner A20");

        JSONObject rk3399 = new JSONObject().put("abi", "arm64-v8a").put("available_processors", 6)
                .put("ui_extra", new JSONObject().put("sys_board_platform", "rk3288"));
        check("Rockchip RK3399".equals(CpuSocResolver.resolve(rk3399).socName), "RK3399 6-core fixup");

        JSONObject k3v2 = new JSONObject().put("abi", "armeabi-v7a").put("available_processors", 4)
                .put("cpu_identity", new JSONObject().put("Hardware", "k3v2oem1"));
        check("HiSilicon K3V2".equals(CpuSocResolver.resolve(k3v2).socName), "HiSilicon K3V2");

        check("Qualcomm Scorpion".equals(CpuSocResolver.decodeArmMicroarchitecture("0x51", "0x00f", -1, -1, false)), "Scorpion");
        check("ARM Cortex-A5".equals(CpuSocResolver.decodeArmMicroarchitecture("0x51", "0x00f", -1, -1, true)), "Cortex-A5");

        // 7. big.LITTLE multi-cluster
        JSONObject bigLittle = new JSONObject()
                .put("abi", "armeabi-v7a")
                .put("available_processors", 8)
                .put("ui_extra", new JSONObject()
                        .put("sys_board_platform", "exynos5")
                        .put("cpu_uarch_clusters", new JSONArray()
                                .put(new JSONObject().put("implementer", "0x41").put("part", "0xc0f").put("count", 4))
                                .put(new JSONObject().put("implementer", "0x41").put("part", "0xc07").put("count", 4))));
        CpuSocResolver.Result rBL = CpuSocResolver.resolve(bigLittle);
        check(rBL.cpuArch.contains("ARM Cortex-A15") && rBL.cpuArch.contains("ARM Cortex-A7"), "big.LITTLE arch: " + rBL.cpuArch);

        // 8. Live device snapshot + ui_extra must resolve cleanly without error
        JSONObject live = DeviceSnapshot.collect(context, false);
        live.put("ui_extra", DeviceHardwareExtra.collect(context));
        CpuSocResolver.Result rLive = CpuSocResolver.resolve(live);
        check(rLive.socName != null && rLive.socName.length() > 0 && !"未知".equals(rLive.socName),
                "live device SoC resolved: " + rLive.socName);
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

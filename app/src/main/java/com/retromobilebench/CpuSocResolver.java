package com.retromobilebench;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline SoC & CPU microarchitecture identification database.
 * Combines signals from /proc/cpuinfo (ARM MIDR implementer/part/variant/revision, Hardware, model name),
 * Android system properties (ro.soc.model, ro.chipname, ro.board.platform, ro.product.board,
 * ro.mediatek.platform, ro.arch), sysfs soc0 attributes, GPU renderer, core count, and max clock.
 * Strictly API 8+ compatible and zero runtime network calls.
 */
final class CpuSocResolver {
    private CpuSocResolver() { }

    static final class Result {
        final String socName;
        final String socVendor;
        final String cpuArch;
        final String cpuArchDetail;
        final String sourceNote;
        final boolean matched;

        Result(String socName, String socVendor, String cpuArch, String cpuArchDetail,
               String sourceNote, boolean matched) {
            this.socName = socName;
            this.socVendor = socVendor;
            this.cpuArch = cpuArch;
            this.cpuArchDetail = cpuArchDetail;
            this.sourceNote = sourceNote;
            this.matched = matched;
        }
    }

    private static final class ChipMatch {
        final String vendor;
        final String series;
        final int model;
        final String suffix;
        final String overrideDisplay;

        ChipMatch(String vendor, String series, int model, String suffix) {
            this(vendor, series, model, suffix, null);
        }

        ChipMatch(String vendor, String series, int model, String suffix, String overrideDisplay) {
            this.vendor = vendor;
            this.series = series;
            this.model = model;
            this.suffix = suffix == null ? "" : suffix;
            this.overrideDisplay = overrideDisplay;
        }
    }

    private static final Pattern PAT_QCOM_MSM_APQ = Pattern.compile("(?i)(?:^|[\\s,/])(MSM|APQ|QSD)\\s?(\\d{4})([A-Za-z0-9\\-]*)");
    private static final Pattern PAT_QCOM_SDM = Pattern.compile("(?i)(?:^|[\\s,/])(SDM)(\\d{3})(?=$|[\\s,/])");
    private static final Pattern PAT_QCOM_SM = Pattern.compile("(?i)(?:^|[\\s,/])(SM)(\\d{4})(?=$|[\\s,/])");
    private static final Pattern PAT_EXYNOS_FULL = Pattern.compile("(?i)(?:samsung\\s+)?exynos\\s?(\\d{4})([A-Za-z]*)");
    private static final Pattern PAT_EXYNOS_SHORT = Pattern.compile("(?i)(?:universal|s5e)(\\d{4})");
    private static final Pattern PAT_MTK = Pattern.compile("(?i)(?:^|[\\s,/])MTK?(\\d{4})([A-Za-z/]*)");
    private static final Pattern PAT_KIRIN = Pattern.compile("(?i)(?:^|[\\s,/])kirin\\s?(\\d{3,4})");
    private static final Pattern PAT_RK = Pattern.compile("(?i)^rk(\\d{4})([a-z]?)$");
    private static final Pattern PAT_SC = Pattern.compile("(?i)^s([cp])(\\d{4})([a-z]*)$");
    private static final Pattern PAT_UNISOC_T = Pattern.compile("(?i)^unisoc\\s+t(\\d{3,4})$");
    private static final Pattern PAT_UNISOC_UMS = Pattern.compile("(?i)^unisoc\\s+ums(\\d{3,4})$");
    private static final Pattern PAT_PXA = Pattern.compile("(?i)^PXA(\\d{3,4}|1L88)$");
    private static final Pattern PAT_BCM = Pattern.compile("(?i)^BCM(\\d{4})$");
    private static final Pattern PAT_OMAP = Pattern.compile("(?i)^OMAP\\s?(\\d{4})$");
    private static final Pattern PAT_SUNXI = Pattern.compile("(?i)^sun(\\d{1,2})i$");
    private static final Pattern PAT_TCC = Pattern.compile("(?i)^tcc(\\d{3})x$");
    private static final Pattern PAT_LC = Pattern.compile("(?i)^lc(\\d{4})([a-z]?)$");

    private static volatile Method sysPropGetMethod;
    private static volatile boolean sysPropLookupDone;

    /** Collects extended offline SoC hints into ui_extra on a background worker thread. */
    static void collectExtra(JSONObject extra) {
        if (extra == null) return;
        try {
            putIfNonEmpty(extra, "sys_board_platform", getSystemProperty("ro.board.platform"));
            putIfNonEmpty(extra, "sys_product_board", getSystemProperty("ro.product.board"));
            putIfNonEmpty(extra, "sys_chipname", getSystemProperty("ro.chipname"));
            putIfNonEmpty(extra, "sys_hardware_chipname", getSystemProperty("ro.hardware.chipname"));
            putIfNonEmpty(extra, "sys_mediatek_platform", getSystemProperty("ro.mediatek.platform"));
            putIfNonEmpty(extra, "sys_arch", getSystemProperty("ro.arch"));
            putIfNonEmpty(extra, "sys_soc_manufacturer", getSystemProperty("ro.soc.manufacturer"));
            putIfNonEmpty(extra, "sys_soc_model", getSystemProperty("ro.soc.model"));

            String socId = firstReadableLine("/sys/devices/soc0/soc_id", "/sys/devices/system/soc/soc0/id");
            String socFamily = firstReadableLine("/sys/devices/soc0/family", "/sys/devices/system/soc/soc0/family");
            String socMachine = firstReadableLine("/sys/devices/soc0/machine", "/sys/devices/system/soc/soc0/machine");
            putIfNonEmpty(extra, "sysfs_soc_id", socId);
            putIfNonEmpty(extra, "sysfs_soc_family", socFamily);
            putIfNonEmpty(extra, "sysfs_soc_machine", socMachine);

            JSONArray clusters = parseCpuInfoClusters();
            if (clusters.length() > 0) {
                extra.put("cpu_uarch_clusters", clusters);
            }
        } catch (JSONException | SecurityException ignored) { }
    }

    /** Resolves SoC marketing/silicon name & CPU microarchitecture from a collected device snapshot. */
    static Result resolve(JSONObject device) {
        if (device == null) {
            return new Result("未知", "", "未知", "", "", false);
        }
        JSONObject cpuId = device.optJSONObject("cpu_identity");
        JSONObject extra = device.optJSONObject("ui_extra");

        String procHardware = cpuId != null ? cpuId.optString("Hardware", "") : "";
        String procProcessor = cpuId != null ? cpuId.optString("Processor", "") : "";
        String procModelName = cpuId != null ? cpuId.optString("model name", "") : "";
        String procVendorId = cpuId != null ? cpuId.optString("vendor_id", "") : "";
        String procFeatures = cpuId != null ? cpuId.optString("Features", cpuId.optString("flags", "")) : "";

        String sysBoardPlatform = extra != null ? extra.optString("sys_board_platform", "") : "";
        String sysProductBoard = extra != null ? extra.optString("sys_product_board", "") : "";
        String sysChipname = extra != null ? extra.optString("sys_chipname", "") : "";
        String sysHwChipname = extra != null ? extra.optString("sys_hardware_chipname", "") : "";
        String sysMtkPlatform = extra != null ? extra.optString("sys_mediatek_platform", "") : "";
        String sysArch = extra != null ? extra.optString("sys_arch", "") : "";
        String sysSocModel = extra != null ? extra.optString("sys_soc_model", "") : "";
        String sysSocMfg = extra != null ? extra.optString("sys_soc_manufacturer", "") : "";
        String sysfsSocId = extra != null ? extra.optString("sysfs_soc_id", "") : "";
        String sysfsSocMachine = extra != null ? extra.optString("sysfs_soc_machine", "") : "";

        String buildHardware = extra != null ? extra.optString("hardware", "") : "";
        String buildBoard = extra != null ? extra.optString("board", "") : "";
        String devCodename = device.optString("device", "");
        String devModel = device.optString("model", "");
        String gpuRenderer = extra != null
                ? firstNonEmpty(extra.optString("gpu_display_name", ""), extra.optString("gpu_renderer", ""))
                : "";
        String abi = device.optString("abi", "");
        boolean isEmulator = device.optBoolean("emulator", false);

        int cores = CpuTopology.workers(device);
        int maxFreqKhz = parseMaxFreqKhz(device);

        // 1. Decode CPU microarchitecture (multi-cluster if available, else single cpu_identity)
        String[] archResult = resolveCpuMicroarchitecture(cpuId, extra, procFeatures, procVendorId, procModelName, abi, isEmulator);
        String cpuArch = archResult[0];
        String cpuArchDetail = archResult[1];

        // 2. Handle x86 / Emulator explicitly first when model_name or x86 ABI applies
        if (abi.startsWith("x86") || "GenuineIntel".equals(procVendorId) || "AuthenticAMD".equals(procVendorId)) {
            Result x86 = resolveX86Soc(procModelName, procVendorId, sysBoardPlatform, buildBoard, buildHardware,
                    procHardware, isEmulator, cpuArch, cpuArchDetail);
            if (x86 != null) return x86;
        }

        // 3. Evaluate ARM / Android SoC identification in reliability priority order
        ChipMatch match = null;

        // 3a. Android 12+ ro.soc.model
        if (sysSocModel.length() > 0) {
            match = matchFromRoSocModel(sysSocModel, sysSocMfg);
        }
        // 3b. ro.hardware.chipname & ro.chipname (Samsung & Qualcomm often report exact SoC here, e.g. OMAP4470)
        if (match == null && sysHwChipname.length() > 0) {
            match = matchFromChipname(sysHwChipname);
        }
        if (match == null && sysChipname.length() > 0) {
            match = matchFromChipname(sysChipname);
        }
        // 3c. sysfs socinfo (/sys/devices/soc0/*)
        if (match == null && sysfsSocMachine.length() > 0) {
            match = matchGenericPlatform(sysfsSocMachine, cores, maxFreqKhz);
        }
        if (match == null && sysfsSocId.length() > 0) {
            match = matchQualcommSocId(sysfsSocId);
        }
        // 3d. /proc/cpuinfo Hardware (plus Tegra special path)
        boolean isTegra = "tegra".equals(sysBoardPlatform) || "tegra3".equals(sysBoardPlatform);
        if (match == null && procHardware.length() > 0) {
            match = matchFromProcCpuinfoHardware(procHardware, cores, maxFreqKhz, isTegra);
        }
        // 3e. ro.mediatek.platform
        if (match == null && sysMtkPlatform.length() > 0) {
            match = matchMt(sysMtkPlatform, false);
        }
        // 3f. ro.arch (Exynos)
        if (match == null && sysArch.length() > 0) {
            match = matchExynos(sysArch);
        }
        // 3g. ro.product.board
        if (match == null && sysProductBoard.length() > 0) {
            match = matchFromBoard(sysProductBoard, cores, maxFreqKhz);
        }
        // 3h. ro.board.platform (with OMAP4 / Exynos4 / Tegra disambiguation)
        if (match == null && sysBoardPlatform.length() > 0) {
            match = matchFromPlatform(sysBoardPlatform, cores, maxFreqKhz, gpuRenderer, devCodename, devModel, buildBoard);
        }
        // 3i. Build.HARDWARE / Build.BOARD / Build.DEVICE fallback (when only DeviceSnapshot exists, e.g. historical reports)
        if (match == null && buildHardware.length() > 0) {
            match = matchFromProcCpuinfoHardware(buildHardware, cores, maxFreqKhz, isTegra);
            if (match == null) match = matchFromPlatform(buildHardware, cores, maxFreqKhz, gpuRenderer, devCodename, devModel, buildBoard);
        }
        if (match == null && buildBoard.length() > 0) {
            match = matchFromBoard(buildBoard, cores, maxFreqKhz);
        }
        if (match == null && devCodename.length() > 0) {
            match = matchKnownDeviceCodename(devCodename, devModel, gpuRenderer, maxFreqKhz, cores);
        }

        if (match != null) {
            match = fixupAndEnrichChipMatch(match, cores, maxFreqKhz, gpuRenderer, devCodename, devModel, procHardware);
            String socName = formatChipMatch(match);
            String note = buildSourceNote(socName, procHardware, sysBoardPlatform, sysChipname, sysProductBoard);
            return new Result(socName, match.vendor, cpuArch, cpuArchDetail, note, true);
        }

        // Fallback to raw strings
        String raw = firstNonEmpty(sysChipname, procHardware, procModelName, sysBoardPlatform, buildHardware, procProcessor);
        if (raw.length() == 0) raw = "未知";
        String note = buildSourceNote(raw, procHardware, sysBoardPlatform, sysChipname, sysProductBoard);
        return new Result(raw, "", cpuArch, cpuArchDetail, note, false);
    }

    private static Result resolveX86Soc(String modelName, String vendorId, String boardPlatform,
                                        String buildBoard, String buildHw, String procHw,
                                        boolean isEmulator, String cpuArch, String cpuArchDetail) {
        String vendor = "GenuineIntel".equals(vendorId) ? "Intel"
                : ("AuthenticAMD".equals(vendorId) ? "AMD" : "x86");
        String cleanedModel = modelName.trim();
        if (isEmulator || "ranchu".equalsIgnoreCase(boardPlatform) || "ranchu".equalsIgnoreCase(buildHw)
                || "goldfish".equalsIgnoreCase(buildHw) || "goldfish".equalsIgnoreCase(procHw)) {
            String plat = firstNonEmpty(boardPlatform, buildHw, procHw, "emulator");
            String soc = cleanedModel.length() > 0
                    ? cleanedModel + " (模拟器 " + plat + ")"
                    : "Android x86 模拟器 (" + plat + ")";
            return new Result(soc, vendor, cpuArch, cpuArchDetail,
                    "运行平台：" + plat + (cleanedModel.length() > 0 ? " · " + cleanedModel : ""), true);
        }
        // Physical x86 Android (e.g. Intel Atom phones/tablets)
        String intelPlatform = mapIntelAndroidPlatform(firstNonEmpty(boardPlatform, buildBoard, buildHw));
        if (cleanedModel.length() > 0) {
            String soc = intelPlatform != null && !cleanedModel.contains("Atom")
                    ? cleanedModel + " (" + intelPlatform + ")"
                    : cleanedModel;
            return new Result(soc, vendor, cpuArch, cpuArchDetail,
                    intelPlatform != null ? "平台：" + firstNonEmpty(boardPlatform, buildBoard) : "", true);
        }
        if (intelPlatform != null) {
            return new Result(intelPlatform, vendor, cpuArch, cpuArchDetail,
                    "平台：" + firstNonEmpty(boardPlatform, buildBoard, buildHw), true);
        }
        return null;
    }

    private static String mapIntelAndroidPlatform(String plat) {
        if (plat == null || plat.length() == 0) return null;
        String lower = plat.toLowerCase(Locale.US);
        if (lower.startsWith("mfld")) return "Intel Atom (Medfield)";
        if (lower.contains("clovertrail") || lower.startsWith("ctp")) return "Intel Atom (Clover Trail+)";
        if (lower.contains("merrifield")) return "Intel Atom (Merrifield)";
        if (lower.contains("moorefield")) return "Intel Atom (Moorefield)";
        if (lower.contains("baytrail") || lower.equals("byt")) return "Intel Atom (Bay Trail)";
        if (lower.contains("cherrytrail") || lower.equals("cht")) return "Intel Atom (Cherry Trail)";
        return null;
    }

    private static String[] resolveCpuMicroarchitecture(JSONObject cpuId, JSONObject extra,
                                                        String features, String vendorId,
                                                        String modelName, String abi, boolean isEmulator) {
        if (abi.startsWith("x86") || "GenuineIntel".equals(vendorId) || "AuthenticAMD".equals(vendorId)) {
            String arch = "GenuineIntel".equals(vendorId) ? "Intel x86"
                    : ("AuthenticAMD".equals(vendorId) ? "AMD x86" : "x86");
            if (isEmulator) arch += " (虚拟化)";
            String detail = modelName.length() > 0 ? modelName : "";
            return new String[]{arch, detail};
        }

        boolean hasVfpv4 = features != null && features.toLowerCase(Locale.US).contains("vfpv4");
        JSONArray clusters = extra != null ? extra.optJSONArray("cpu_uarch_clusters") : null;
        if (clusters != null && clusters.length() > 0) {
            StringBuilder shortSb = new StringBuilder();
            StringBuilder detailSb = new StringBuilder();
            for (int i = 0; i < clusters.length(); i++) {
                JSONObject c = clusters.optJSONObject(i);
                if (c == null) continue;
                int count = c.optInt("count", 1);
                String implStr = c.optString("implementer", "");
                String partStr = c.optString("part", "");
                int var = parseHexInt(c.optString("variant", ""), -1);
                int rev = parseHexInt(c.optString("revision", ""), -1);
                String decoded = decodeArmMicroarchitecture(implStr, partStr, var, rev, hasVfpv4);
                if (decoded == null) continue;
                if (shortSb.length() > 0) shortSb.append(" + ");
                if (clusters.length() > 1) shortSb.append(count).append("× ");
                shortSb.append(decoded);

                if (detailSb.length() > 0) detailSb.append(" · ");
                detailSb.append(count).append("× ").append(decoded);
                if (var >= 0 && rev >= 0) detailSb.append(" (r").append(var).append("p").append(rev).append(')');
                detailSb.append(" [").append(implStr).append('/').append(partStr).append(']');
            }
            if (shortSb.length() > 0) {
                return new String[]{shortSb.toString(), detailSb.toString()};
            }
        }

        if (cpuId != null) {
            String implStr = cpuId.optString("CPU implementer", "");
            String partStr = cpuId.optString("CPU part", "");
            int var = parseHexInt(cpuId.optString("CPU variant", ""), -1);
            int rev = parseHexInt(cpuId.optString("CPU revision", ""), -1);
            String decoded = decodeArmMicroarchitecture(implStr, partStr, var, rev, hasVfpv4);
            if (decoded != null) {
                StringBuilder detail = new StringBuilder();
                String cpuArchVer = cpuId.optString("CPU architecture", "");
                if (cpuArchVer.length() > 0) detail.append("ARMv").append(cpuArchVer);
                if (var >= 0 && rev >= 0) {
                    if (detail.length() > 0) detail.append(" · ");
                    detail.append("r").append(var).append("p").append(rev);
                }
                if (implStr.length() > 0 && partStr.length() > 0) {
                    if (detail.length() > 0) detail.append(" · ");
                    detail.append("Impl ").append(implStr).append(" / Part ").append(partStr);
                }
                return new String[]{decoded, detail.toString()};
            }
        }

        String proc = cpuId != null ? cpuId.optString("Processor", "") : "";
        return new String[]{proc.length() > 0 ? proc : "未知", ""};
    }

    /** Maps ARM MIDR implementer + part (+ variant for Qualcomm Krait) from pytorch/cpuinfo uarch.c. */
    static String decodeArmMicroarchitecture(String implHex, String partHex, int variant, int revision, boolean hasVfpv4) {
        int impl = parseHexInt(implHex, -1);
        int part = parseHexInt(partHex, -1);
        if (impl < 0 || part < 0) return null;

        switch (impl) {
            case 0x41: // 'A' ARM
                switch (part) {
                    case 0xC05: return "ARM Cortex-A5";
                    case 0xC07: return "ARM Cortex-A7";
                    case 0xC08: return "ARM Cortex-A8";
                    case 0xC09: return "ARM Cortex-A9";
                    case 0xC0C: return "ARM Cortex-A12";
                    case 0xC0D: return "ARM Cortex-A12 / A17";
                    case 0xC0E: return "ARM Cortex-A17";
                    case 0xC0F: return "ARM Cortex-A15";
                    case 0xD01: return "ARM Cortex-A32";
                    case 0xD03: return "ARM Cortex-A53";
                    case 0xD04: return "ARM Cortex-A35";
                    case 0xD05: return "ARM Cortex-A55";
                    case 0xD06: return "ARM Cortex-A65";
                    case 0xD07: return "ARM Cortex-A57";
                    case 0xD08: return "ARM Cortex-A72";
                    case 0xD09: return "ARM Cortex-A73";
                    case 0xD0A: return "ARM Cortex-A75";
                    case 0xD0B:
                    case 0xD0E: return "ARM Cortex-A76";
                    case 0xD0C: return "ARM Neoverse N1";
                    case 0xD0D: return "ARM Cortex-A77";
                    case 0xD40: return "ARM Neoverse V1";
                    case 0xD41:
                    case 0xD4B: return "ARM Cortex-A78";
                    case 0xD44: return "ARM Cortex-X1";
                    case 0xD46: return "ARM Cortex-A510";
                    case 0xD47: return "ARM Cortex-A710";
                    case 0xD48: return "ARM Cortex-X2";
                    case 0xD49: return "ARM Neoverse N2";
                    case 0xD4D: return "ARM Cortex-A715";
                    case 0xD4E: return "ARM Cortex-X3";
                    case 0xD4F: return "ARM Neoverse V2";
                    case 0xD80: return "ARM Cortex-A520";
                    case 0xD81: return "ARM Cortex-A720";
                    case 0xD82: return "ARM Cortex-X4";
                    case 0xD85: return "ARM Cortex-X925";
                    case 0xD87: return "ARM Cortex-A725";
                    case 0xB02: return "ARM11 MPCore";
                    case 0xB36: return "ARM1136";
                    case 0xB56: return "ARM1156";
                    case 0xB76: return "ARM1176";
                    default:
                        int archHigh = part >> 8;
                        if (archHigh == 7) return "ARM7";
                        if (archHigh == 9) return "ARM9";
                        if (archHigh == 11) return "ARM11";
                        return String.format(Locale.US, "ARM (0x%03X)", part);
                }
            case 0x51: // 'Q' Qualcomm
                switch (part) {
                    case 0x00F:
                        return hasVfpv4 ? "ARM Cortex-A5" : "Qualcomm Scorpion";
                    case 0x02D:
                        return "Qualcomm Scorpion (Dual-core)";
                    case 0x04D:
                        if (variant == 1) return "Qualcomm Krait 200";
                        if (variant == 2) return "Qualcomm Krait 300";
                        return "Qualcomm Krait";
                    case 0x06F:
                        if (variant == 0) return "Qualcomm Krait 200";
                        if (variant == 1) return "Qualcomm Krait 300";
                        if (variant == 2) return "Qualcomm Krait 400";
                        if (variant == 3) return "Qualcomm Krait 450";
                        return "Qualcomm Krait";
                    case 0x201:
                    case 0x211:
                        return "Qualcomm Kryo Silver";
                    case 0x205:
                        return "Qualcomm Kryo Gold";
                    case 0x800:
                        return "Qualcomm Kryo 260/280 Gold";
                    case 0x801:
                        return "Qualcomm Kryo 260/280 Silver";
                    case 0x802:
                        return "Qualcomm Kryo 385 Gold";
                    case 0x803:
                        return "Qualcomm Kryo 385 Silver";
                    case 0x804:
                        return "Qualcomm Kryo 485 Gold";
                    case 0x805:
                        return "Qualcomm Kryo 485 Silver";
                    default:
                        return String.format(Locale.US, "Qualcomm (0x%03X)", part);
                }
            case 0x53: // 'S' Samsung
                switch (part) {
                    case 0x001: return "Samsung Exynos M1/M2";
                    case 0x002: return "Samsung Exynos M3";
                    case 0x003: return "Samsung Exynos M4";
                    case 0x004: return "Samsung Exynos M5";
                    default: return String.format(Locale.US, "Samsung (0x%03X)", part);
                }
            case 0x4E: // 'N' Nvidia
                switch (part) {
                    case 0x000: return "NVIDIA Denver";
                    case 0x003: return "NVIDIA Denver 2";
                    case 0x004: return "NVIDIA Carmel";
                    default: return String.format(Locale.US, "NVIDIA (0x%03X)", part);
                }
            case 0x42: // 'B' Broadcom
                if (part == 0x00F) return "Broadcom Brahma-B15";
                if (part == 0x100) return "Broadcom Brahma-B53";
                return String.format(Locale.US, "Broadcom (0x%03X)", part);
            case 0x48: // 'H' HiSilicon
                if (part == 0xD40) return "ARM Cortex-A76 (HiSilicon)";
                if (part == 0xD01) return "HiSilicon TaiShan v110";
                return String.format(Locale.US, "HiSilicon (0x%03X)", part);
            case 0x69: // 'i' Intel XScale
                int high = part >> 8;
                if (high == 2 || high == 4 || high == 6) return "Intel XScale";
                return String.format(Locale.US, "Intel ARM (0x%03X)", part);
            default:
                return String.format(Locale.US, "ARM Impl 0x%02X / Part 0x%03X", impl, part);
        }
    }

    private static ChipMatch matchFromRoSocModel(String socModel, String socMfg) {
        ChipMatch m = matchQcom(socModel);
        if (m != null) return m;
        m = matchMt(socModel, true);
        if (m != null) return m;
        m = matchExynos(socModel);
        if (m != null) return m;
        if (socModel.startsWith("Tensor")) {
            return new ChipMatch("Google", "Tensor", 0, socModel.substring(6).trim());
        }
        if (socModel.startsWith("GS201")) {
            return new ChipMatch("Google", "Tensor", 0, "G2");
        }
        if (socMfg.length() > 0) {
            return new ChipMatch(socMfg, "", 0, "", socMfg + " " + socModel);
        }
        return new ChipMatch("", "", 0, "", socModel);
    }

    private static ChipMatch matchFromChipname(String chipname) {
        ChipMatch m = matchQcom(chipname);
        if (m != null) return m;
        m = matchOmap(chipname);
        if (m != null) return m;
        m = matchExynos(chipname);
        if (m != null) return m;
        m = matchMt(chipname, true);
        if (m != null) return m;
        m = matchSc(chipname);
        if (m != null) return m;
        m = matchPxa(chipname);
        if (m != null) return m;
        if ("mp523x".equalsIgnoreCase(chipname)) {
            return new ChipMatch("Renesas", "MP", 5232, "");
        }
        return null;
    }

    private static ChipMatch matchGenericPlatform(String str, int cores, int maxFreqKhz) {
        ChipMatch m = matchQcom(str);
        if (m != null) return m;
        m = matchOmap(str);
        if (m != null) return m;
        m = matchExynos(str);
        if (m != null) return m;
        m = matchMt(str, true);
        if (m != null) return m;
        m = matchKirin(str);
        if (m != null) return m;
        m = matchRk(str);
        if (m != null) return m;
        m = matchSc(str);
        if (m != null) return m;
        return null;
    }

    private static ChipMatch matchFromProcCpuinfoHardware(String hw, int cores, int maxFreqKhz, boolean isTegra) {
        if (isTegra) {
            ChipMatch t = matchTegraBoard(hw);
            if (t != null) return t;
        }
        ChipMatch m = matchQcom(hw);
        if (m != null) return m;
        m = matchMt(hw, true);
        if (m != null) return m;
        m = matchKirin(hw);
        if (m != null) return m;
        m = matchRk(hw);
        if (m != null) return m;
        m = matchExynos(hw);
        if (m != null) return m;
        m = matchSmdk(hw, cores);
        if (m != null) return m;
        m = matchSc(hw);
        if (m != null) return m;
        m = matchUnisoc(hw);
        if (m != null) return m;
        m = matchPxa(hw);
        if (m != null) return m;
        m = matchSunxi(hw, cores);
        if (m != null) return m;
        m = matchBcm(hw);
        if (m != null) return m;
        m = matchOmap(hw);
        if (m != null) return m;
        m = matchWmt(hw, cores, maxFreqKhz);
        if (m != null) return m;
        m = matchTcc(hw);
        if (m != null) return m;
        ChipMatch special = matchSpecialHardware(hw);
        return special != null ? special : matchSpecialBoard(hw);
    }

    private static ChipMatch matchFromBoard(String board, int cores, int maxFreqKhz) {
        ChipMatch m = matchQcom(board);
        if (m != null) return m;
        m = matchExynos(board);
        if (m != null) return m;
        m = matchSmdk(board, cores);
        if (m != null) return m;
        m = matchMt(board, true);
        if (m != null) return m;
        m = matchSc(board);
        if (m != null) return m;
        m = matchPxa(board);
        if (m != null) return m;
        m = matchLc(board);
        if (m != null) return m;
        m = matchBroadcomPlatform(board, cores, maxFreqKhz);
        if (m != null) return m;
        m = matchHuaweiBoard(board);
        if (m != null) return m;
        return matchSpecialBoard(board);
    }

    private static ChipMatch matchFromPlatform(String platform, int cores, int maxFreqKhz,
                                                 String gpuRenderer, String devCodename,
                                                 String devModel, String buildBoard) {
        ChipMatch m = matchQcom(platform);
        if (m != null) return m;
        m = matchExynos(platform);
        if (m != null) return m;
        m = matchMt(platform, true);
        if (m != null) return m;
        m = matchKirin(platform);
        if (m != null) return m;
        m = matchSc(platform);
        if (m != null) return m;
        m = matchRk(platform);
        if (m != null) return m;
        m = matchLc(platform);
        if (m != null) return m;
        m = matchHuaweiBoard(platform);
        if (m != null) return m;
        m = matchBroadcomPlatform(platform, cores, maxFreqKhz);
        if (m != null) return m;

        String lower = platform.toLowerCase(Locale.US);
        if ("omap4".equals(lower)) {
            return disambiguateOmap4(cores, maxFreqKhz, gpuRenderer, devCodename, devModel, buildBoard);
        }
        if ("omap3".equals(lower)) {
            return new ChipMatch("Texas Instruments", "OMAP", maxFreqKhz >= 900000 ? 3630 : 3430, "");
        }
        if ("exynos4".equals(lower)) {
            int model = cores >= 4 ? 4412 : (maxFreqKhz >= 1350000 ? 4212 : 4210);
            return new ChipMatch("Samsung", "Exynos", model, "");
        }
        if ("exynos5".equals(lower)) {
            return new ChipMatch("Samsung", "Exynos", cores >= 8 ? 5410 : 5250, "");
        }
        if ("tegra".equals(lower)) {
            return new ChipMatch("NVIDIA", "Tegra ", 2, "");
        }
        if ("tegra3".equals(lower)) {
            return new ChipMatch("NVIDIA", "Tegra ", 3, "");
        }
        if ("tegra4".equals(lower)) {
            return new ChipMatch("NVIDIA", "Tegra ", 4, "");
        }
        if ("rk29xx".equals(lower)) return new ChipMatch("Rockchip", "RK", 2918, "");
        if ("rk30xx".equals(lower)) return new ChipMatch("Rockchip", "RK", 3066, "");
        if ("rk31xx".equals(lower)) return new ChipMatch("Rockchip", "RK", 3188, "");

        // Amlogic
        if ("meson3".equals(lower)) return new ChipMatch("Amlogic", "AML", 8726, "-M");
        if ("meson6".equals(lower)) return new ChipMatch("Amlogic", "AML", 8726, "-MX");
        if ("meson8".equals(lower)) return new ChipMatch("Amlogic", "S", 805, "");
        if ("gxbaby".equals(lower)) return new ChipMatch("Amlogic", "S", 905, "");
        if ("gxl".equals(lower)) return new ChipMatch("Amlogic", "S", 905, "X");
        if ("gxm".equals(lower)) return new ChipMatch("Amlogic", "S", 912, "");

        // Special platform entries
        if ("hi6620oem".equals(lower)) return new ChipMatch("HiSilicon", "Kirin", 910, "T");
        if ("hi6250".equals(lower)) return new ChipMatch("HiSilicon", "Kirin", 650, "");
        if ("hi6210sft".equals(lower)) return new ChipMatch("HiSilicon", "Kirin", 620, "");
        if ("hi3630".equals(lower)) return new ChipMatch("HiSilicon", "Kirin", 920, "");
        if ("hi3635".equals(lower)) return new ChipMatch("HiSilicon", "Kirin", 930, "");
        if ("hi3650".equals(lower)) return new ChipMatch("HiSilicon", "Kirin", 950, "");
        if ("hi3660".equals(lower)) return new ChipMatch("HiSilicon", "Kirin", 960, "");
        if ("k3v2oem1".equals(lower) || "k3v200".equals(lower)) return new ChipMatch("HiSilicon", "K3V", 2, "");
        if ("montblanc".equals(lower)) return new ChipMatch("ST-Ericsson", "NovaThor U", 8500, "");
        if ("song".equals(lower)) return new ChipMatch("Pinecone", "Surge S", 1, "");
        if ("rk322x".equals(lower)) return new ChipMatch("Rockchip", "RK", 3229, "");
        if ("tegra132".equals(lower)) return new ChipMatch("NVIDIA", "Tegra ", 0, "K1 (T132)");
        if ("tegra210_dragon".equals(lower)) return new ChipMatch("NVIDIA", "Tegra ", 0, "X1 (T210)");
        if ("s5pc110".equals(lower)) return new ChipMatch("Samsung", "Exynos", 3110, "");
        return null;
    }

    private static ChipMatch disambiguateOmap4(int cores, int maxFreqKhz, String gpuRenderer,
                                               String devCodename, String devModel, String buildBoard) {
        String gpuLower = gpuRenderer.toLowerCase(Locale.US);
        String devLower = devCodename.toLowerCase(Locale.US);
        String modLower = devModel.toLowerCase(Locale.US);
        String boardLower = buildBoard.toLowerCase(Locale.US);
        if (gpuLower.contains("544") || devLower.startsWith("superior") || modLower.contains("i9260")
                || devLower.equals("jem") || devLower.equals("ovation") || devLower.equals("hummingbird")
                || maxFreqKhz >= 1450000) {
            return new ChipMatch("Texas Instruments", "OMAP", 4470, "");
        }
        if (devLower.equals("maguro") || devLower.equals("toro") || devLower.equals("toroplus")
                || devLower.equals("tuna") || boardLower.equals("tuna")
                || (maxFreqKhz >= 1180000 && maxFreqKhz < 1450000)) {
            return new ChipMatch("Texas Instruments", "OMAP", 4460, "");
        }
        if ((cores == 2 && maxFreqKhz >= 900000 && maxFreqKhz <= 1150000)
                || devLower.startsWith("espresso") || devLower.equals("solana") || devLower.equals("targa")) {
            return new ChipMatch("Texas Instruments", "OMAP", 4430, "");
        }
        return new ChipMatch("Texas Instruments", "OMAP", 4, "");
    }

    private static ChipMatch matchKnownDeviceCodename(String devCodename, String devModel,
                                                      String gpuRenderer, int maxFreqKhz, int cores) {
        String dev = devCodename.toLowerCase(Locale.US);
        if (dev.startsWith("superior") || devModel.toUpperCase(Locale.US).contains("GT-I9260")) {
            return new ChipMatch("Texas Instruments", "OMAP", 4470, "");
        }
        if ("taurus".equals(dev) || devModel.toUpperCase(Locale.US).contains("MI 2A")) {
            return new ChipMatch("Qualcomm", "MSM", 8960, "T");
        }
        if ("aries".equals(dev) && gpuRenderer.contains("320")) {
            return new ChipMatch("Qualcomm", "APQ", 8064, "");
        }
        if ("mako".equals(dev)) return new ChipMatch("Qualcomm", "APQ", 8064, "");
        if ("hammerhead".equals(dev)) return new ChipMatch("Qualcomm", "MSM", 8974, "");
        if ("maguro".equals(dev) || "toro".equals(dev) || "toroplus".equals(dev)) {
            return new ChipMatch("Texas Instruments", "OMAP", 4460, "");
        }
        if ("grouper".equals(dev) || "tilapia".equals(dev)) {
            return new ChipMatch("NVIDIA", "Tegra ", 3, " (T30L)");
        }
        if ("crespo".equals(dev) || "herring".equals(dev)) {
            return new ChipMatch("Samsung", "Exynos", 3110, "");
        }
        return matchSpecialBoard(devCodename);
    }

    private static ChipMatch matchQualcommSocId(String socIdStr) {
        int id;
        try {
            id = Integer.parseInt(socIdStr.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        switch (id) {
            case 87:
            case 122:
            case 123:
            case 124:
                return new ChipMatch("Qualcomm", "MSM", 8960, "");
            case 109:
                return new ChipMatch("Qualcomm", "APQ", 8064, "");
            case 126:
            case 184:
            case 185:
            case 186:
                return new ChipMatch("Qualcomm", "MSM", 8974, "");
            case 138:
            case 139:
            case 140:
            case 141:
                return new ChipMatch("Qualcomm", "MSM", 8960, "AB");
            case 153:
                return new ChipMatch("Qualcomm", "APQ", 8064, "AB");
            case 194:
                return new ChipMatch("Qualcomm", "MSM", 8974, "PRO-AC");
            case 206:
                return new ChipMatch("Qualcomm", "MSM", 8916, "");
            case 239:
                return new ChipMatch("Qualcomm", "MSM", 8939, "");
            case 246:
                return new ChipMatch("Qualcomm", "MSM", 8996, "");
            case 292:
                return new ChipMatch("Qualcomm", "MSM", 8998, "");
            default:
                return null;
        }
    }

    private static ChipMatch matchQcom(String text) {
        Matcher m = PAT_QCOM_MSM_APQ.matcher(text);
        if (m.find()) {
            String series = m.group(1).toUpperCase(Locale.US);
            int model = Integer.parseInt(m.group(2));
            String suffix = m.group(3) != null ? m.group(3).toUpperCase(Locale.US) : "";
            if (suffix.equals(series)) suffix = "";
            return new ChipMatch("Qualcomm", series, model, suffix);
        }
        Matcher sdm = PAT_QCOM_SDM.matcher(text);
        if (sdm.find()) {
            return new ChipMatch("Qualcomm", "Snapdragon", Integer.parseInt(sdm.group(2)), "");
        }
        Matcher sm = PAT_QCOM_SM.matcher(text);
        if (sm.find()) {
            return new ChipMatch("Qualcomm", "SM", Integer.parseInt(sm.group(2)), "");
        }
        return null;
    }

    private static ChipMatch matchExynos(String text) {
        Matcher f = PAT_EXYNOS_FULL.matcher(text);
        if (f.find()) {
            String suf = f.group(2) != null ? f.group(2).toUpperCase(Locale.US) : "";
            return new ChipMatch("Samsung", "Exynos", Integer.parseInt(f.group(1)), suf);
        }
        Matcher s = PAT_EXYNOS_SHORT.matcher(text);
        if (s.find()) {
            return new ChipMatch("Samsung", "Exynos", Integer.parseInt(s.group(1)), "");
        }
        return null;
    }

    private static ChipMatch matchSmdk(String text, int cores) {
        if ("SMDK4210".equalsIgnoreCase(text)) {
            return new ChipMatch("Samsung", "Exynos", 4210, "");
        }
        if ("SMDK4x12".equalsIgnoreCase(text)) {
            if (cores == 2) return new ChipMatch("Samsung", "Exynos", 4212, "");
            if (cores == 4) return new ChipMatch("Samsung", "Exynos", 4412, "");
        }
        return null;
    }

    private static ChipMatch matchMt(String text, boolean matchEnd) {
        Matcher m = PAT_MTK.matcher(text);
        if (m.find()) {
            if (matchEnd && m.end() != text.length()) {
                // Allow trailing whitespace or comma after MTxxxx in Hardware string
                String tail = text.substring(m.end()).trim();
                if (tail.length() > 0 && !tail.startsWith(",") && !tail.startsWith("(")) {
                    return null;
                }
            }
            int model = Integer.parseInt(m.group(1));
            String suf = m.group(2) != null ? m.group(2).toUpperCase(Locale.US) : "";
            if ("TURBO".equals(suf) || "TRUBO".equals(suf)) suf = "T";
            return new ChipMatch("MediaTek", "MT", model, suf);
        }
        return null;
    }

    private static ChipMatch matchKirin(String text) {
        Matcher m = PAT_KIRIN.matcher(text);
        if (m.find()) {
            return new ChipMatch("HiSilicon", "Kirin", Integer.parseInt(m.group(1)), "");
        }
        return null;
    }

    private static ChipMatch matchRk(String text) {
        Matcher m = PAT_RK.matcher(text.trim());
        if (m.matches()) {
            String suf = m.group(2) != null ? m.group(2).toUpperCase(Locale.US) : "";
            return new ChipMatch("Rockchip", "RK", Integer.parseInt(m.group(1)), suf);
        }
        return null;
    }

    private static ChipMatch matchSc(String text) {
        String trimmed = text.trim();
        if ("scx15".equalsIgnoreCase(trimmed)) {
            return new ChipMatch("Spreadtrum", "SC", 7715, "");
        }
        Matcher m = PAT_SC.matcher(trimmed);
        if (m.matches()) {
            String suf = m.group(3) != null ? m.group(3).toUpperCase(Locale.US) : "";
            return new ChipMatch("Spreadtrum", "SC", Integer.parseInt(m.group(2)), suf);
        }
        return null;
    }

    private static ChipMatch matchUnisoc(String text) {
        String trimmed = text.trim();
        Matcher mt = PAT_UNISOC_T.matcher(trimmed);
        if (mt.matches()) {
            return new ChipMatch("Unisoc", "T", Integer.parseInt(mt.group(1)), "");
        }
        Matcher mu = PAT_UNISOC_UMS.matcher(trimmed);
        if (mu.matches()) {
            return new ChipMatch("Unisoc", "UMS", Integer.parseInt(mu.group(1)), "");
        }
        return null;
    }

    private static ChipMatch matchPxa(String text) {
        Matcher m = PAT_PXA.matcher(text.trim());
        if (m.matches()) {
            String num = m.group(1);
            int model = "1L88".equals(num) ? 1088 : Integer.parseInt(num);
            return new ChipMatch("Marvell", "PXA", model, "");
        }
        return null;
    }

    private static ChipMatch matchBcm(String text) {
        Matcher m = PAT_BCM.matcher(text.trim());
        if (m.matches()) {
            return new ChipMatch("Broadcom", "BCM", Integer.parseInt(m.group(1)), "");
        }
        return null;
    }

    private static ChipMatch matchOmap(String text) {
        Matcher m = PAT_OMAP.matcher(text.trim());
        if (m.matches()) {
            return new ChipMatch("Texas Instruments", "OMAP", Integer.parseInt(m.group(1)), "");
        }
        return null;
    }

    private static ChipMatch matchSunxi(String text, int cores) {
        Matcher m = PAT_SUNXI.matcher(text.trim());
        if (!m.matches()) return null;
        int sunxi = Integer.parseInt(m.group(1));
        if (sunxi == 4 && cores == 1) return new ChipMatch("Allwinner", "A", 10, "");
        if (sunxi == 5 && cores == 1) return new ChipMatch("Allwinner", "A", 13, "");
        if (sunxi == 6 && cores == 4) return new ChipMatch("Allwinner", "A", 31, "");
        if (sunxi == 7 && cores == 2) return new ChipMatch("Allwinner", "A", 20, "");
        if (sunxi == 8 && cores == 2) return new ChipMatch("Allwinner", "A", 23, "");
        if (sunxi == 8 && cores == 4) return new ChipMatch("Allwinner", "A", 33, "");
        if (sunxi == 8 && cores == 8) return new ChipMatch("Allwinner", "A", 83, "T");
        if (sunxi == 9 && cores == 8) return new ChipMatch("Allwinner", "A", 80, "");
        if (sunxi == 50 && cores == 4) return new ChipMatch("Allwinner", "A", 64, "");
        return new ChipMatch("Allwinner", "sun" + sunxi + "i", 0, "");
    }

    private static ChipMatch matchWmt(String text, int cores, int maxFreqKhz) {
        if (!"WMT".equals(text.trim())) return null;
        if (cores == 1 && maxFreqKhz == 1008000) return new ChipMatch("WonderMedia", "WM", 8950, "");
        if (cores == 1 && maxFreqKhz == 1200000) return new ChipMatch("WonderMedia", "WM", 8850, "");
        if (cores == 2 && maxFreqKhz == 1500000) return new ChipMatch("WonderMedia", "WM", 8880, "");
        return new ChipMatch("WonderMedia", "WMT", 0, "");
    }

    private static ChipMatch matchTcc(String text) {
        Matcher m = PAT_TCC.matcher(text.trim());
        if (m.matches()) {
            return new ChipMatch("Telechips", "TCC", Integer.parseInt(m.group(1)), "X");
        }
        return null;
    }

    private static ChipMatch matchLc(String text) {
        Matcher m = PAT_LC.matcher(text.trim());
        if (m.matches()) {
            String suf = m.group(2) != null ? m.group(2).toUpperCase(Locale.US) : "";
            return new ChipMatch("Leadcore", "LC", Integer.parseInt(m.group(1)), suf);
        }
        return null;
    }

    private static ChipMatch matchBroadcomPlatform(String text, int cores, int maxFreqKhz) {
        String lower = text.trim().toLowerCase(Locale.US);
        if ("rhea".equals(lower) && cores == 1) {
            return new ChipMatch("Broadcom", "BCM", 21654, maxFreqKhz >= 999999 ? "G" : "");
        }
        if ("java".equals(lower) && cores == 4) {
            return new ChipMatch("Broadcom", "BCM", 23550, "");
        }
        if ("hawaii".equals(lower)) {
            if (cores == 1) return new ChipMatch("Broadcom", "BCM", 21663, "");
            if (cores == 2) return new ChipMatch("Broadcom", "BCM", 21664, maxFreqKhz >= 1200000 ? "T" : "");
        }
        if ("capri".equals(lower) && cores == 2) {
            return new ChipMatch("Broadcom", "BCM", 28155, "");
        }
        return null;
    }

    private static ChipMatch matchHuaweiBoard(String text) {
        if (text.length() < 3) return null;
        if (text.length() != 3 && text.length() != 7 && text.length() != 8) return null;
        if (text.length() > 3) {
            if (text.charAt(3) != '-' || text.charAt(text.length() - 3) != 'L') return null;
        }
        String p = text.substring(0, 3);
        if ("EVA".equals(p) || "VIE".equals(p)) return new ChipMatch("HiSilicon", "Kirin", 955, "");
        if ("FRD".equals(p) || "KNT".equals(p) || "NXT".equals(p)) return new ChipMatch("HiSilicon", "Kirin", 950, "");
        if ("DUK".equals(p) || "LON".equals(p) || "MHA".equals(p) || "STF".equals(p)
                || "VKY".equals(p) || "VTR".equals(p)) return new ChipMatch("HiSilicon", "Kirin", 960, "");
        if ("ALP".equals(p) || "BLA".equals(p) || "BKL".equals(p) || "CLT".equals(p) || "COL".equals(p)
                || "COR".equals(p) || "EML".equals(p) || "NEO".equals(p) || "PAR".equals(p)
                || "RVL".equals(p)) return new ChipMatch("HiSilicon", "Kirin", 970, "");
        if ("LYA".equals(p) || "MCN".equals(p) || "PAN".equals(p) || "SUE".equals(p)) {
            return new ChipMatch("HiSilicon", "Kirin", 980, "");
        }
        if ("BAC".equals(p)) return new ChipMatch("HiSilicon", "Kirin", 659, "");
        if ("INE".equals(p)) return new ChipMatch("HiSilicon", "Kirin", 710, "");
        return null;
    }

    private static ChipMatch matchSpecialHardware(String hw) {
        if ("Superior".equalsIgnoreCase(hw)) return new ChipMatch("Texas Instruments", "OMAP", 4470, "");
        if ("Tuna".equalsIgnoreCase(hw)) return new ChipMatch("Texas Instruments", "OMAP", 4460, "");
        if ("mapphone_CDMA".equalsIgnoreCase(hw)) return new ChipMatch("Texas Instruments", "OMAP", 4430, "");
        if ("Manta".equalsIgnoreCase(hw)) return new ChipMatch("Samsung", "Exynos", 5250, "");
        if ("k3v2oem1".equalsIgnoreCase(hw)) return new ChipMatch("HiSilicon", "K3V", 2, "");
        if ("hi6620oem".equalsIgnoreCase(hw)) return new ChipMatch("HiSilicon", "Kirin", 910, "T");
        if ("hi6250".equalsIgnoreCase(hw)) return new ChipMatch("HiSilicon", "Kirin", 650, "");
        if ("hi6210sft".equalsIgnoreCase(hw)) return new ChipMatch("HiSilicon", "Kirin", 620, "");
        if ("hi3630".equalsIgnoreCase(hw)) return new ChipMatch("HiSilicon", "Kirin", 920, "");
        if ("hi3635".equalsIgnoreCase(hw)) return new ChipMatch("HiSilicon", "Kirin", 930, "");
        if ("gs702a".equalsIgnoreCase(hw)) return new ChipMatch("Actions", "ATM", 7029, "");
        if ("gs702c".equalsIgnoreCase(hw)) return new ChipMatch("Actions", "ATM", 7029, "B");
        if ("gs703d".equalsIgnoreCase(hw)) return new ChipMatch("Actions", "ATM", 7039, "S");
        if ("gs705a".equalsIgnoreCase(hw)) return new ChipMatch("Actions", "ATM", 7059, "A");
        if ("Amlogic Meson8".equalsIgnoreCase(hw)) return new ChipMatch("Amlogic", "S", 812, "");
        if ("Amlogic Meson8B".equalsIgnoreCase(hw)) return new ChipMatch("Amlogic", "S", 805, "");
        if ("Odin".equalsIgnoreCase(hw)) return new ChipMatch("LG", "Nuclun", 7111, "");
        if ("Madison".equalsIgnoreCase(hw)) return new ChipMatch("MStar", "6A", 338, "");
        return null;
    }

    private static ChipMatch matchTegraBoard(String hw) {
        if ("cardhu".equals(hw) || "MT799".equals(hw) || "chagall".equals(hw) || "sphinx".equals(hw)
                || "tostab11BS".equals(hw) || "picasso_m".equals(hw) || "picasso_mf".equals(hw)
                || "WIKIPAD".equals(hw)) return new ChipMatch("NVIDIA", "Tegra ", 3, " (T30)");
        if ("kai".equals(hw) || "m470".equals(hw) || "txs03".equals(hw) || "BIRCH".equals(hw)
                || "tostab12AL".equals(hw) || "tostab12BL".equals(hw) || "a110".equals(hw)
                || "picasso_e2".equals(hw) || "avalon".equals(hw) || "NS_14T004".equals(hw)
                || "grouper".equals(hw) || "tilapia".equals(hw)) {
            return new ChipMatch("NVIDIA", "Tegra ", 3, " (T30L)");
        }
        if ("endeavoru".equals(hw) || "tegra_fjdev101".equals(hw) || "x3".equals(hw) || "vu10".equals(hw)) {
            return new ChipMatch("NVIDIA", "Tegra ", 3, " (AP33)");
        }
        if ("evitareul".equals(hw) || "enrc2b".equals(hw) || "bobsleigh".equals(hw) || "tegra_fjdev103".equals(hw)) {
            return new ChipMatch("NVIDIA", "Tegra ", 3, "+ (T33)");
        }
        if ("p3".equals(hw) || "SHW-M380S".equals(hw) || "ventana".equals(hw) || "nbx03".equals(hw)
                || "antares".equals(hw) || "vangogh".equals(hw)) {
            return new ChipMatch("NVIDIA", "Tegra ", 2, " (T20)");
        }
        if ("n1".equals(hw) || "stingray".equals(hw) || "picasso_e".equals(hw) || "picasso".equals(hw)) {
            return new ChipMatch("NVIDIA", "Tegra ", 2, " (AP20H)");
        }
        if ("mozart".equals(hw) || "tegratab".equals(hw) || "roth".equals(hw) || "pisces".equals(hw)
                || "t8400n".equals(hw) || "macallan".equals(hw) || "maya".equals(hw)
                || "tostab12BA".equals(hw) || "kb".equals(hw)) {
            return new ChipMatch("NVIDIA", "Tegra ", 4, " (T114)");
        }
        if ("tn8".equals(hw) || "mocha".equals(hw)) {
            return new ChipMatch("NVIDIA", "Tegra ", 0, "K1 (T124)");
        }
        if ("foster_e".equals(hw) || "foster_e_hdd".equals(hw) || "darcy".equals(hw)) {
            return new ChipMatch("NVIDIA", "Tegra ", 0, "X1 (T210)");
        }
        if ("Ceres".equals(hw)) {
            return new ChipMatch("NVIDIA", "Tegra ", 4, "i (SL460N)");
        }
        return null;
    }

    private static ChipMatch matchSpecialBoard(String board) {
        if ("tuna".equalsIgnoreCase(board)) return new ChipMatch("Texas Instruments", "OMAP", 4460, "");
        if ("grouper".equalsIgnoreCase(board) || "tilapia".equalsIgnoreCase(board)) {
            return new ChipMatch("NVIDIA", "Tegra ", 3, " (T30L)");
        }
        if ("flounder".equalsIgnoreCase(board)) return new ChipMatch("NVIDIA", "Tegra ", 0, "K1 (T132)");
        if ("dragon".equalsIgnoreCase(board)) return new ChipMatch("NVIDIA", "Tegra ", 0, "X1 (T210)");
        if ("sailfish".equalsIgnoreCase(board) || "marlin".equalsIgnoreCase(board)) {
            return new ChipMatch("Qualcomm", "MSM", 8996, "PRO");
        }
        if ("hws7701u".equalsIgnoreCase(board)) return new ChipMatch("Rockchip", "RK", 3168, "");
        if ("K00F".equalsIgnoreCase(board)) return new ChipMatch("Rockchip", "RK", 3188, "");
        if ("T7H".equalsIgnoreCase(board)) return new ChipMatch("Rockchip", "RK", 3066, "");
        if ("g2mv".equalsIgnoreCase(board)) return new ChipMatch("NVIDIA", "Tegra ", 4, "i (SL460N)");
        if ("BEETHOVEN".equalsIgnoreCase(board)) return new ChipMatch("HiSilicon", "Kirin", 950, "");
        if ("mp523x".equalsIgnoreCase(board)) return new ChipMatch("Renesas", "MP", 5232, "");
        return null;
    }

    /** Applies pytorch/cpuinfo fixups (MSM8960->APQ8064, SMDK4x12, RK3288->RK3399, etc.) and marketing labels. */
    private static ChipMatch fixupAndEnrichChipMatch(ChipMatch match, int cores, int maxFreqKhz,
                                                     String gpuRenderer, String devCodename,
                                                     String devModel, String procHardware) {
        String vendor = match.vendor;
        String series = match.series;
        int model = match.model;
        String suffix = match.suffix;

        if ("Qualcomm".equals(vendor) && "MSM".equals(series)) {
            if (suffix.length() == 0) {
                if (model == 8216) model = 8916;
                else if (model == 8916 && cores == 8) model = 8939;
                else if (model == 8937 && cores == 4) model = 8917;
                else if (model == 8996 && cores == 8) model = 8994;
                else if (model == 8610 && cores == 4) model = 8612;
                else if (model == 8960) {
                    if (cores == 4) {
                        series = "APQ";
                        model = 8064;
                    } else if (cores == 2 && (gpuRenderer.contains("320")
                            || "taurus".equalsIgnoreCase(devCodename)
                            || devModel.toUpperCase(Locale.US).contains("MI 2A")
                            || maxFreqKhz >= 1650000)) {
                        suffix = "T";
                    }
                }
            } else if ("SG".equals(suffix) && model == 8976) {
                suffix = "PRO";
            }
        } else if ("Samsung".equals(vendor) && "Exynos".equals(series)) {
            if (model == 4410) model = 4412;
            else if (model == 5420 && cores == 6) model = 5260;
            else if (model == 7580 && cores == 4) model = 7578;
        } else if ("MediaTek".equals(vendor) && "MT".equals(series)) {
            if (model == 6752 && cores == 4) model = 6732;
        } else if ("Rockchip".equals(vendor) && "RK".equals(series)) {
            if (model == 3288 && cores == 6) model = 3399;
        }

        // Enrich retro Qualcomm / MediaTek / Samsung with well-known Snapdragon / marketing names
        if ("Qualcomm".equals(vendor)) {
            String fullPart = series + (model > 0 ? String.valueOf(model) : "") + suffix;
            String snapName = qualcommMarketingName(series, model, suffix);
            if (snapName != null) {
                return new ChipMatch(vendor, series, model, suffix, "Qualcomm " + snapName + " (" + fullPart + ")");
            }
        } else if ("MediaTek".equals(vendor) && "MT".equals(series) && model == 6795) {
            return new ChipMatch(vendor, series, model, suffix, "MediaTek Helio X10 (MT6795" + suffix + ")");
        } else if ("Samsung".equals(vendor) && "Exynos".equals(series) && model == 3110) {
            return new ChipMatch(vendor, series, model, suffix, "Samsung Exynos 3110 (S5PC110)");
        }

        return new ChipMatch(vendor, series, model, suffix, match.overrideDisplay);
    }

    private static String qualcommMarketingName(String series, int model, String suffix) {
        if ("QSD".equals(series)) {
            if (model == 8250 || model == 8650) return "Snapdragon S1";
        } else if ("MSM".equals(series)) {
            if (model == 7225 || model == 7227 || model == 7625 || model == 7627) return "Snapdragon S1";
            if (model == 7230 || model == 8255 || model == 8655) return "Snapdragon S2";
            if (model == 8260 || model == 8660) return "Snapdragon S3";
            if (model == 8225 || model == 8625 || model == 8227 || model == 8627) return "Snapdragon S4 Play";
            if (model == 8930) return "Snapdragon 400 / S4 Plus";
            if (model == 8960) {
                return (suffix.contains("T") || suffix.contains("AB") || suffix.contains("DT"))
                        ? "Snapdragon S4 Pro" : "Snapdragon S4 Plus";
            }
            if (model == 8226 || model == 8626 || model == 8926 || model == 8928) return "Snapdragon 400";
            if (model == 8916) return "Snapdragon 410";
            if (model == 8939) return "Snapdragon 615";
            if (model == 8952) return "Snapdragon 617";
            if (model == 8953) return "Snapdragon 625";
            if (model == 8974) return suffix.contains("PRO") || suffix.contains("AC") ? "Snapdragon 801" : "Snapdragon 800";
            if (model == 8992) return "Snapdragon 808";
            if (model == 8994) return "Snapdragon 810";
            if (model == 8996) return "Snapdragon 820";
            if (model == 8998) return "Snapdragon 835";
        } else if ("APQ".equals(series)) {
            if (model == 8060) return "Snapdragon S3";
            if (model == 8064) return suffix.contains("AB") || suffix.contains("1AA") ? "Snapdragon 600" : "Snapdragon S4 Pro";
            if (model == 8084) return "Snapdragon 805";
        }
        return null;
    }

    private static String formatChipMatch(ChipMatch m) {
        if (m.overrideDisplay != null && m.overrideDisplay.length() > 0) {
            return m.overrideDisplay;
        }
        StringBuilder sb = new StringBuilder();
        if (m.vendor.length() > 0) sb.append(m.vendor);
        if (m.series.length() > 0) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(m.series);
            if ("Exynos".equals(m.series) || "Kirin".equals(m.series) || "OMAP".equals(m.series)
                    || "Snapdragon".equals(m.series) || "Nuclun".equals(m.series) || "Tensor".equals(m.series)) {
                if (m.model > 0 || m.suffix.length() > 0) sb.append(' ');
            }
        }
        if (m.model > 0) sb.append(m.model);
        if (m.suffix.length() > 0) sb.append(m.suffix);
        return sb.toString().trim();
    }

    private static String buildSourceNote(String resolvedName, String procHw, String platform,
                                          String chipname, String board) {
        List<String> parts = new ArrayList<>();
        if (procHw.length() > 0 && !resolvedName.equalsIgnoreCase(procHw)) {
            parts.add("Hardware: " + procHw);
        }
        if (platform.length() > 0 && !resolvedName.toLowerCase(Locale.US).contains(platform.toLowerCase(Locale.US))) {
            parts.add("平台: " + platform);
        }
        if (chipname.length() > 0 && !resolvedName.toLowerCase(Locale.US).contains(chipname.toLowerCase(Locale.US))) {
            parts.add("芯片: " + chipname);
        }
        if (parts.isEmpty() && board.length() > 0 && !resolvedName.toLowerCase(Locale.US).contains(board.toLowerCase(Locale.US))) {
            parts.add("主板: " + board);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(" · ");
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    private static JSONArray parseCpuInfoClusters() throws JSONException {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, JSONObject> templates = new LinkedHashMap<>();

        String curImpl = "";
        String curPart = "";
        String curVar = "";
        String curRev = "";
        boolean hasBlockData = false;

        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/cpuinfo"))) {
            String line;
            int lines = 0;
            while (lines++ < 512 && (line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.length() == 0) {
                    if (hasBlockData && curImpl.length() > 0 && curPart.length() > 0) {
                        recordCluster(counts, templates, curImpl, curPart, curVar, curRev);
                    }
                    curImpl = "";
                    curPart = "";
                    curVar = "";
                    curRev = "";
                    hasBlockData = false;
                    continue;
                }
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String key = line.substring(0, colon).trim();
                String val = line.substring(colon + 1).trim();
                if ("CPU implementer".equals(key)) { curImpl = val; hasBlockData = true; }
                else if ("CPU part".equals(key)) { curPart = val; hasBlockData = true; }
                else if ("CPU variant".equals(key)) { curVar = val; hasBlockData = true; }
                else if ("CPU revision".equals(key)) { curRev = val; hasBlockData = true; }
            }
            if (hasBlockData && curImpl.length() > 0 && curPart.length() > 0) {
                recordCluster(counts, templates, curImpl, curPart, curVar, curRev);
            }
        } catch (IOException | SecurityException ignored) { }

        JSONArray arr = new JSONArray();
        for (Map.Entry<String, JSONObject> entry : templates.entrySet()) {
            JSONObject obj = entry.getValue();
            obj.put("count", counts.get(entry.getKey()));
            arr.put(obj);
        }
        return arr;
    }

    private static void recordCluster(Map<String, Integer> counts, Map<String, JSONObject> templates,
                                      String impl, String part, String var, String rev) throws JSONException {
        String key = impl.toLowerCase(Locale.US) + "|" + part.toLowerCase(Locale.US) + "|" + var.toLowerCase(Locale.US);
        Integer prev = counts.get(key);
        counts.put(key, prev == null ? 1 : prev + 1);
        if (!templates.containsKey(key)) {
            JSONObject obj = new JSONObject();
            obj.put("implementer", impl);
            obj.put("part", part);
            if (var.length() > 0) obj.put("variant", var);
            if (rev.length() > 0) obj.put("revision", rev);
            templates.put(key, obj);
        }
    }

    private static int parseMaxFreqKhz(JSONObject device) {
        int max = parseIntSafe(device.optString("cpu0_max_frequency_khz", ""), 0);
        JSONArray cores = device.optJSONArray("cpu_cores");
        if (cores != null) {
            for (int i = 0; i < cores.length(); i++) {
                JSONObject c = cores.optJSONObject(i);
                if (c != null) {
                    int f = parseIntSafe(c.optString("max_frequency_khz", ""), 0);
                    if (f > max) max = f;
                }
            }
        }
        return max;
    }

    private static int parseHexInt(String s, int fallback) {
        if (s == null) return fallback;
        String t = s.trim();
        if (t.length() == 0) return fallback;
        try {
            if (t.startsWith("0x") || t.startsWith("0X")) {
                return Integer.parseInt(t.substring(2), 16);
            }
            return Integer.parseInt(t);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int parseIntSafe(String s, int fallback) {
        if (s == null) return fallback;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static String getSystemProperty(String key) {
        try {
            if (!sysPropLookupDone) {
                sysPropLookupDone = true;
                Class<?> sp = Class.forName("android.os.SystemProperties");
                sysPropGetMethod = sp.getMethod("get", String.class, String.class);
            }
            if (sysPropGetMethod != null) {
                Object res = sysPropGetMethod.invoke(null, key, "");
                return res instanceof String ? ((String) res).trim() : "";
            }
        } catch (Throwable ignored) { }
        return "";
    }

    private static String firstReadableLine(String... paths) {
        for (String path : paths) {
            File f = new File(path);
            if (!f.isFile()) continue;
            try (BufferedReader reader = new BufferedReader(new FileReader(f))) {
                String line = reader.readLine();
                if (line != null && line.trim().length() > 0) return line.trim();
            } catch (IOException | SecurityException ignored) { }
        }
        return "";
    }

    private static void putIfNonEmpty(JSONObject obj, String key, String val) throws JSONException {
        if (val != null && val.length() > 0) obj.put(key, val);
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && v.trim().length() > 0) return v.trim();
        }
        return "";
    }
}

package com.retromobilebench;

import android.annotation.TargetApi;
import android.media.MediaCodecInfo;
import org.json.JSONObject;

@TargetApi(29)
final class CodecAttributesApi29 {
    static JSONObject describe(MediaCodecInfo info) throws Exception {
        return new JSONObject().put("source", "manufacturer_reported_attributes")
                .put("hardware_accelerated", info.isHardwareAccelerated()).put("software_only", info.isSoftwareOnly());
    }
}

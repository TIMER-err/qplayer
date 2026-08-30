package dev.t1m3.qplayer.util;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Source-neutral QR encoder used by standardized plugin login challenges. */
public final class QrMatrix {
    private QrMatrix() {}

    public static List<List<Boolean>> encode(String content) {
        if (content == null || content.isEmpty()) return java.util.Collections.emptyList();
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 0);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            BitMatrix bits = new QRCodeWriter().encode(
                    content, BarcodeFormat.QR_CODE, 0, 0, hints);
            List<List<Boolean>> rows = new ArrayList<>(bits.getHeight());
            for (int y = 0; y < bits.getHeight(); y++) {
                List<Boolean> row = new ArrayList<>(bits.getWidth());
                for (int x = 0; x < bits.getWidth(); x++) row.add(bits.get(x, y));
                rows.add(row);
            }
            return rows;
        } catch (Throwable error) {
            Logger.warn("QR encode failed: {}", error.getMessage());
            return java.util.Collections.emptyList();
        }
    }
}

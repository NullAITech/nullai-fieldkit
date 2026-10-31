package tech.nullai.fieldkit;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * YUV_420_888 to JPEG with correct stride handling.
 *
 * <p>Reading the Y plane as one flat block shears the picture whenever the
 * capture row stride is wider than the image (on the Pixel it usually is), so
 * the plane is copied row by row and the chroma planes are interleaved
 * V-then-U honouring their own strides. Shared by the relay capture path and
 * the in-call camera so both produce the same quality.
 */
public final class JpegUtil {

    private JpegUtil() { }

    public static byte[] yuvToJpeg(Image img, int quality) {
        try {
            Image.Plane[] planes = img.getPlanes();
            int w = img.getWidth(), h = img.getHeight();
            byte[] nv21 = new byte[w * h * 3 / 2];
            ByteBuffer y = planes[0].getBuffer();
            int yRow = planes[0].getRowStride();
            int pos = 0;
            if (yRow == w) {
                y.get(nv21, 0, w * h);
                pos = w * h;
            } else {
                byte[] row = new byte[yRow];
                for (int r = 0; r < h; r++) {
                    y.position(r * yRow);
                    int n = Math.min(yRow, y.remaining());
                    if (n <= 0) break;
                    y.get(row, 0, n);
                    System.arraycopy(row, 0, nv21, pos, w);
                    pos += w;
                }
            }
            ByteBuffer u = planes[1].getBuffer(), v = planes[2].getBuffer();
            int uvRow = planes[1].getRowStride();
            int uvPix = planes[1].getPixelStride();
            int cw = w / 2, ch = h / 2;
            for (int r = 0; r < ch; r++) {
                for (int c = 0; c < cw; c++) {
                    int idx = r * uvRow + c * uvPix;
                    if (idx + 1 > v.limit() || idx + 1 > u.limit() || pos + 1 >= nv21.length) break;
                    nv21[pos++] = v.get(idx);
                    nv21[pos++] = u.get(idx);
                }
            }
            YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, w, h, null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuv.compressToJpeg(new Rect(0, 0, w, h), quality, out);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }
}

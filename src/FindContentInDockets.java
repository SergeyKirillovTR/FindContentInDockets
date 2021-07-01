import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;

import java.util.List;
import java.util.zip.GZIPInputStream;

public class FindContentInDockets {

    private static final int[] fromBase64 = new int[256];
    private static boolean isMIME = false;

    private static final char[] toBase64 = {
            'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M',
            'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z',
            'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm',
            'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '+', '/'
    };

    static {
        Arrays.fill(fromBase64, -1);
        for (int i = 0; i < toBase64.length; i++)
            fromBase64[toBase64[i]] = i;
        fromBase64['='] = -2;
    }

    public static void main(String[] args) {
        String path = args[0];
        String contentToFind = args[1];

        findContentInFiles(path, contentToFind);
    }

    private static void findContentInFiles(String pathName, String contentToFind) {
        File file = new File(pathName);
        File[] files = file.listFiles();
        int foundedFilesQty = 0;

        if (files != null) {
            for (File gzipFile : files) {
                if (findContent(gzipFile, contentToFind)) {
                    System.out.println(gzipFile.getName());
                    foundedFilesQty++;
                }
            }
        }

        if (foundedFilesQty == 0) {
            System.out.println("No content found: " + contentToFind);
        }
    }

    private static boolean findContent(File file, String contentToFind) {
        String unzippedFile = unzipFile(file);
        String[] pages = extractContent(unzippedFile);
        for (String page : pages) {
            String decodedPage = "";

            try {
                decodedPage = decodePage(page);
            } catch (IllegalArgumentException e) {
                System.out.println(e.getMessage() + "In file: " + file.getName());
            }

            if (decodedPage.toUpperCase().contains(contentToFind.toUpperCase())) {
                return true;
            }
        }
        return false;
    }

    private static String decodePage(String page) {
        StringBuilder sb = new StringBuilder();
        byte[] decode = decode(page);
        for (byte b : decode) {
            sb.append((char) b);
        }
        return sb.toString();
    }

    private static byte[] decode(String src) {
        return decode(src.getBytes(Charset.forName("ISO-8859-1")));
    }

    private static byte[] decode(byte[] src) {
        byte[] dst = new byte[outLength(src, 0, src.length)];
        int ret = decode0(src, 0, src.length, dst);
        if (ret != dst.length) {
            dst = Arrays.copyOf(dst, ret);
        }
        return dst;
    }

    private static int outLength(byte[] src, int sp, int sl) {
        int[] base64 = fromBase64;
        int paddings = 0;
        int len = sl - sp;
        if (len == 0)
            return 0;
        if (len < 2) {
            if (isMIME && base64[0] == -1)
                return 0;
            throw new IllegalArgumentException(
                    "Input byte[] should at least have 2 bytes for base64 bytes");
        }
        if (isMIME) {
            // scan all bytes to fill out all non-alphabet. a performance
            // trade-off of pre-scan or Arrays.copyOf
            int n = 0;
            while (sp < sl) {
                int b = src[sp++] & 0xff;
                if (b == '=') {
                    len -= (sl - sp + 1);
                    break;
                }
                if ((b = base64[b]) == -1)
                    n++;
            }
            len -= n;
        } else {
            if (src[sl - 1] == '=') {
                paddings++;
                if (src[sl - 2] == '=')
                    paddings++;
            }
        }
        if (paddings == 0 && (len & 0x3) !=  0)
            paddings = 4 - (len & 0x3);
        return 3 * ((len + 3) / 4) - paddings;
    }

    private static int decode0(byte[] src, int sp, int sl, byte[] dst) {
        int[] base64 = fromBase64;
        int dp = 0;
        int bits = 0;
        int shiftto = 18;       // pos of first byte of 4-byte atom
        while (sp < sl) {
            int b = src[sp++] & 0xff;
            if ((b = base64[b]) < 0) {
                if (b == -2) {         // padding byte '='
                    // =     shiftto==18 unnecessary padding
                    // x=    shiftto==12 a dangling single x
                    // x     to be handled together with non-padding case
                    // xx=   shiftto==6&&sp==sl missing last =
                    // xx=y  shiftto==6 last is not =
                    if (shiftto == 6 && (sp == sl || src[sp++] != '=') ||
                            shiftto == 18) {
                        throw new IllegalArgumentException(
                                "Input byte array has wrong 4-byte ending unit");
                    }
                    break;
                }
                if (isMIME)    // skip if for rfc2045
                    continue;
                else
                    throw new IllegalArgumentException(
                            "Illegal base64 character " +
                                    Integer.toString(src[sp - 1], 16));
            }
            bits |= (b << shiftto);
            shiftto -= 6;
            if (shiftto < 0) {
                dst[dp++] = (byte)(bits >> 16);
                dst[dp++] = (byte)(bits >>  8);
                dst[dp++] = (byte)(bits);
                shiftto = 18;
                bits = 0;
            }
        }
        // reached end of byte array or hit padding '=' characters.
        if (shiftto == 6) {
            dst[dp++] = (byte)(bits >> 16);
        } else if (shiftto == 0) {
            dst[dp++] = (byte)(bits >> 16);
            dst[dp++] = (byte)(bits >>  8);
        } else if (shiftto == 12) {
            // dangling single "x", incorrectly encoded.
            throw new IllegalArgumentException(
                    "Last unit does not have enough valid bits");
        }
        // anything left is invalid, if is not MIME.
        // if MIME, ignore all non-base64 character
        while (sp < sl) {
            if (isMIME && base64[src[sp++]] < 0)
                continue;
            throw new IllegalArgumentException(
                    "Input byte array has incorrect ending byte at " + sp);
        }
        return dp;
    }

    private static String[] extractContent(String unzippedFile) {
        String content = unzippedFile.replaceAll("<docket .*>", "").replaceAll("</docket>", "")
                .replaceAll("<page .*>", "").replaceAll("\\s", "");
        return content.split("</page>");
    }

    private static String unzipFile(File file) {
        StringBuilder sb = new StringBuilder();

        if (file.getName().contains(".gz")) {
            try {
                GZIPInputStream gzipInputStream = new GZIPInputStream(new FileInputStream(file));
                int c;

                while ((c = gzipInputStream.read()) > 0) {
                    sb.append((char) c);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return sb.toString();
    }
}

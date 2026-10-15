package miui.util;
import android.os.Build;
import java.util.Arrays;
public final class WarsawHapticPatterns {
    private WarsawHapticPatterns() {}
    private static final int[] EFFECTS = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 23, 24, 25, 26, 27, 28, 30, 31, 32, 33, 35, 36, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61, 62, 63, 64, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76, 77, 78, 79, 80, 81, 82, 83, 84, 85, 86, 87, 88, 90, 91, 92, 93, 96, 98, 99, 101, 102, 103, 104, 106, 107, 108, 109, 110, 111, 112, 113, 114, 115, 116, 118, 119, 120, 121, 122, 123, 124, 125, 126, 127, 128, 130, 131, 132, 133, 134, 135, 137, 140, 141, 142, 143, 144, 145, 146, 147, 148, 149, 150, 151, 152, 153, 154, 155, 156, 157, 158, 159, 160, 161, 162, 163, 164, 165, 166, 167, 168, 169, 170, 171, 172, 173, 174, 175, 176, 177, 178, 179, 180, 181, 182, 183, 184, 185, 186, 187, 188, 189, 190, 191, 192, 201, 202, 203, 204, 205, 206, 207, 208, 209, 210, 211, 212, 213, 214, 215, 216, 217, 401, 402, 403, 405, 406, 407, 408, 410, 521};
    public static boolean isSupported(int id) {
        return "warsaw".equals(Build.DEVICE) && Arrays.binarySearch(EFFECTS, id) >= 0;
    }
    public static boolean isMotorUi(int id) {
        switch (id) {
            case 0:
            case 1:
            case 3:
            case 9:
            case 268435456:
            case 268435457:
            case 268435458:
            case 268435459:
            case 268435460:
            case 268435461:
            case 268435462:
            case 268435463:
            case 268435464:
            case 268435465:
            case 268435466:
            case 268435467:
            case 268435468:
            case 268435469:
            case 268435470:
            case 268435471:
            case 268435472:
            case 268435473:
            case 268435474:
            case 268435475:
            case 268435476:
            case 268435477:
            case 268435478:
            case 268435479:
            case 268435480:
                return "warsaw".equals(Build.DEVICE);
            default: return false;
        }
    }
    public static int[] forUi(int id) {
        if (!"warsaw".equals(Build.DEVICE)) return null;
        switch (id) {
            case 0: return new int[] {0, 1};
            case 1: return new int[] {5, 2};
            case 3: return new int[] {3, 2};
            case 9: return new int[] {5, 1};
            case 268435456: return new int[] {3, 2};
            case 268435457: return new int[] {5, 2};
            case 268435458: return new int[] {5, 2};
            case 268435459: return new int[] {9, 2};
            case 268435460: return new int[] {8, 2};
            case 268435461: return new int[] {5, 2};
            case 268435462: return new int[] {5, 1};
            case 268435463: return new int[] {0, 1};
            case 268435464: return new int[] {6, 2};
            case 268435465: return new int[] {6, 1};
            case 268435466: return new int[] {2, 2};
            case 268435467: return new int[] {7, 0};
            case 268435468: return new int[] {2, 0};
            case 268435469: return new int[] {7, 2};
            case 268435470: return new int[] {4, 0};
            case 268435471: return new int[] {0, 2};
            case 268435472: return new int[] {1, 2};
            case 268435473: return new int[] {2, 2};
            case 268435474: return new int[] {3, 2};
            case 268435475: return new int[] {4, 2};
            case 268435476: return new int[] {5, 2};
            case 268435477: return new int[] {6, 2};
            case 268435478: return new int[] {7, 2};
            case 268435479: return new int[] {8, 2};
            case 268435480: return new int[] {9, 2};
            default: return null;
        }
    }
}

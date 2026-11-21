package com.muoniumplayer.core.ncm.music.acoustics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用合成体素世界(无游戏依赖)验证射线场的统计物理。 */
class AcousticRayFieldTest {

    private static final float[] STONE = {1.5f, 1.5f, 1.45f};
    private static final float[] WOOL = {0.2f, 0.1f, 0.03f};

    /** 解析定义的体素世界:solid(x,y,z) 为真即实心整块;材质由 wool 区分。 */
    private static final class Voxels implements AcousticRayField.Tracer {
        interface Solid {
            boolean at(int x, int y, int z);
        }

        private final Solid solid;
        private final float[] rho;

        Voxels(Solid solid, float[] rho) {
            this.solid = solid;
            this.rho = rho;
        }

        @Override
        public Object blockKeyAt(double x, double y, double z) {
            return key((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        }

        private static Object key(int x, int y, int z) {
            return x + "," + y + "," + z;
        }

        @Override
        public AcousticRayField.Hit cast(double fx, double fy, double fz,
                                         double tx, double ty, double tz, Object ignoreKey) {
            final double len = Math.sqrt((tx - fx) * (tx - fx) + (ty - fy) * (ty - fy)
                    + (tz - fz) * (tz - fz));
            if (len < 1e-9) {
                return null;
            }
            final double step = 0.02;
            final int n = (int) Math.ceil(len / step);
            int px = (int) Math.floor(fx);
            int py = (int) Math.floor(fy);
            int pz = (int) Math.floor(fz);
            for (int i = 1; i <= n; i++) {
                final double t = Math.min(1.0, i * step / len);
                final double x = fx + (tx - fx) * t;
                final double y = fy + (ty - fy) * t;
                final double z = fz + (tz - fz) * t;
                final int cx = (int) Math.floor(x);
                final int cy = (int) Math.floor(y);
                final int cz = (int) Math.floor(z);
                if ((cx != px || cy != py || cz != pz) && solid.at(cx, cy, cz)
                        && !key(cx, cy, cz).equals(ignoreKey)) {
                    // 二分求交:把命中点落回方块边界,避免反射点嵌进实心格
                    double lo = Math.max(0.0, (i - 1) * step / len);
                    double hi = t;
                    for (int k = 0; k < 14; k++) {
                        final double mid = 0.5 * (lo + hi);
                        final boolean inCell = (int) Math.floor(fx + (tx - fx) * mid) == cx
                                && (int) Math.floor(fy + (ty - fy) * mid) == cy
                                && (int) Math.floor(fz + (tz - fz) * mid) == cz;
                        if (inCell) {
                            hi = mid;
                        } else {
                            lo = mid;
                        }
                    }
                    final double hx = fx + (tx - fx) * hi;
                    final double hy = fy + (ty - fy) * hi;
                    final double hz = fz + (tz - fz) * hi;
                    // 法线:步进可能同时跨过多个轴的格面,取命中点离「已跨越格面」最近的轴
                    final double dxFace = cx != px ? Math.abs(hx - (cx > px ? cx : cx + 1)) : Double.MAX_VALUE;
                    final double dyFace = cy != py ? Math.abs(hy - (cy > py ? cy : cy + 1)) : Double.MAX_VALUE;
                    final double dzFace = cz != pz ? Math.abs(hz - (cz > pz ? cz : cz + 1)) : Double.MAX_VALUE;
                    int nx = 0;
                    int ny = 0;
                    int nz = 0;
                    if (dxFace <= dyFace && dxFace <= dzFace) {
                        nx = cx > px ? -1 : 1;
                    } else if (dyFace <= dzFace) {
                        ny = cy > py ? -1 : 1;
                    } else {
                        nz = cz > pz ? -1 : 1;
                    }
                    return new AcousticRayField.Hit(hx, hy, hz, nx, ny, nz, key(cx, cy, cz));
                }
                px = cx;
                py = cy;
                pz = cz;
            }
            return null;
        }

        @Override
        public float[] reflectivity(Object blockKey) {
            return rho;
        }
    }

    private static AcousticRayField.Stats cast(Voxels.Solid solid,
                                               float[] rho, double ex, double ey, double ez) {
        return AcousticRayField.cast(new Voxels(solid, rho), ex, ey, ez, ex, ey, ez, 512);
    }

    @Test
    @DisplayName("旷野石头地板:晚期围合度≈0,不长出大厅混响")
    void openFieldStoneFloorIsNotEnclosed() {
        final AcousticRayField.Stats s = cast((x, y, z) -> y < 0, STONE, 0.5, 1.6, 0.5);
        assertTrue(s.enclosure > 0.3f, "朝下半球都撞地板,首命中占比约半: " + s.enclosure);
        assertTrue(s.trapped < 0.05f, "地板反射后朝天逃逸,晚期围合应≈0: " + s.trapped);
        assertTrue(s.escape > 0.9f, "命中后基本都飞向开阔: " + s.escape);
    }

    @Test
    @DisplayName("封闭石室:晚期围合度高、几乎没有逃逸")
    void closedStoneRoomIsTrapped() {
        final Voxels.Solid room = (x, y, z) -> x < 0 || x > 7 || y < 0 || y > 5 || z < 0 || z > 7;
        final AcousticRayField.Stats s = cast(room, STONE, 3.5, 1.6, 3.5);
        assertTrue(s.trapped > 0.9f, "封闭室应近乎全部被困: " + s.trapped);
        assertTrue(s.escape < 0.02f, "封闭室不应有逃逸: " + s.escape);
        assertTrue(s.reachable > 0.5f, "封闭室内大部分反射应可回到耳朵: " + s.reachable);
        assertTrue(s.tapCount > 100, "封闭室应有密集抽头: " + s.tapCount);
    }

    @Test
    @DisplayName("单面墙加地板:仍只是回声,不是混响")
    void singleWallInFieldIsEchoNotReverb() {
        final Voxels.Solid wall = (x, y, z) -> y < 0 || x > 12;
        final AcousticRayField.Stats s = cast(wall, STONE, 0.5, 1.6, 0.5);
        assertTrue(s.trapped < 0.15f, "单墙+地板的围合应很低: " + s.trapped);
    }

    @Test
    @DisplayName("羊毛房的高频能量衰减比石室快得多(逐频带连乘)")
    void woolRoomLosesHighBandFaster() {
        final Voxels.Solid room = (x, y, z) -> x < 0 || x > 7 || y < 0 || y > 5 || z < 0 || z > 7;
        final AcousticRayField.Stats stone = cast(room, STONE, 3.5, 1.6, 3.5);
        final AcousticRayField.Stats wool = cast(room, WOOL, 3.5, 1.6, 3.5);
        final double stoneHigh = stone.histogram.totalEnergy(2) / stone.histogram.totalEnergy(1);
        final double woolHigh = wool.histogram.totalEnergy(2) / wool.histogram.totalEnergy(1);
        assertTrue(woolHigh < stoneHigh * 0.5,
                "羊毛高/中频能量比应远小于石头: wool=" + woolHigh + " stone=" + stoneHigh);
    }

    @Test
    @DisplayName("石室的直方图 T60 明显长于羊毛室")
    void stoneRoomRingsLongerThanWoolRoom() {
        final Voxels.Solid room = (x, y, z) -> x < 0 || x > 11 || y < 0 || y > 7 || z < 0 || z > 11;
        final HistogramEstimator.Result stone = HistogramEstimator.estimate(
                cast(room, STONE, 5.5, 1.6, 5.5).histogram);
        final HistogramEstimator.Result wool = HistogramEstimator.estimate(
                cast(room, WOOL, 5.5, 1.6, 5.5).histogram);
        assertTrue(stone != null && wool != null);
        assertTrue(wool.decayMidSec < stone.decayMidSec * 0.7
                        || wool.decayMidSec <= 0.0,
                "wool=" + wool.decayMidSec + " stone=" + stone.decayMidSec);
    }

    @Test
    @DisplayName("没有任何命中返回 NONE")
    void noHitsGivesNone() {
        final AcousticRayField.Stats s = cast((x, y, z) -> false, STONE, 0.5, 1.6, 0.5);
        assertEquals(AcousticRayField.Stats.NONE, s);
    }
}

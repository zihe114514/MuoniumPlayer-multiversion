package com.muoniumplayer.core.jdsp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JamesDSP 混响（sndfilter {@code sf_reverb}）的 1:1 对齐测试。
 *
 * <p>参考数据由<b>独立编写的 Python 严格 float32 复刻</b>（直接对照上游
 * {@code reverb.c} 逐行翻译）生成：3 个用例（44.1k Default / 44.1k Plate High（过采样 ×2）/
 * 48k Default）× 256 帧脉冲响应，base64 内嵌在本类里（避免改五树 build.gradle 的
 * test 资源接线）。两版独立实现逐样本吻合，即证明 Java 移植忠实且无笔误。</p>
 */
class JdspReverbTest {

    /** 参考数据：magic "JREV" + 条目数 + [rate, preset, frames, float32×frames×2]。 */
    private static final String REFERENCE_B64 =
            "VkVSSgMAAABErAAAAAAAAAABAAC2s+Q+AAAAALaz5D4AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
            + "AAAAAAAAANHT6jwAAAAAwPXOuwAAAAA7oh+9AAAAALddibwAAAAA9f0evAAAAABg4aI5AAAAANuTIjsAAAAA0z3aOwAAAADKFd47AAAAAC98"
            + "BjwAAAAA4jv3OwAAAAAkFf07AAAAACDw3zsAAAAApGHUOwAAAAAsX7c7AAAAALmmpTsAAAAAhpmLOwAAAAAZ1nE7AAAAAMo4RTsAAAAA03oi"
            + "OwAAAAAQhPo6AAAAAFC5vToAAAAAnaaAOgAAAADSsho6AAAAANBEXzkAAAAAzMHkuAAAAAC+Ode5AAAAAHSXL7oAAAAARNFtugAAAADutJG6"
            + "AAAAAEjDqboAAAAATWK+ugAAAACeiNC6AAAAADjq37oAAAAAtyPtugAAAAC6Hfi6AAAAAACgALsAAAAAtEYEuwAAAADqJge7AAAAABxICbsA"
            + "AAAATsQKuwAAAABIpQu7AAAAAKL+C7sAAAAAitoLuwAAAAA0SAu7AAAAAElRCrsAAAAA4gEJuwAAAAC1Yge7AAAAAI19BbsAAAAAEVoDuwAA"
            + "AABCAAG7AAAAAHLt/LoAAAAAHIj3ugAAAAC62/G6AAAAACnz67oAAAAA4tflugAAAADgkt+6AAAAAA4s2boAAAAAzqrSugAAAACzFcy6AAAA"
            + "AMxyxboAAAAAgse+ugAAAADJGLi6AAAAAAtrsboAAAAAScKqugAAAAAYIqS6AAAAAK6NnboAAAAA5AeXugAAAABKk5C6AAAAAB0yiroAAAAA"
            + "W+aDugAAAACAY3u6AAAAAKMrb7oAAAAAuCdjugAAAAADWle6AAAAAGrES7oAAAAAgmhAugAAAACYRzW6AAAAALhiKroAAAAAsLofugAAAAAW"
            + "UBW6AAAAAFEjC7oAAAAAljQBugAAAADxB++5AAAAAMQi3LkAAAAANrnJuQAAAACkyre5AAAAAC5WprkAAAAAzFqVuQAAAABF14S5AAAAAHiU"
            + "abkAAAAAcGRKuQAAAAA6Gyy5AAAAAHa1DrkAAAAAK1/kuAAAAADJC624AAAAAFLSbrgAAAAA3t4GuAAAAADItAi3AAAAANpLfTcAAAAAE7Id"
            + "OAAAAADUAHk4AAAAAPenqDgAAAAAFVjTOAAAAAAjmfw4AAAAAMI5EjkAAAAAyXclOQAAAADNCjg5AAAAAOv2STkAAAAAOEBbOQAAAADC6ms5"
            + "AAAAAIj6ezkAAAAAwLmFOQAAAADELI05AAAAAD1YlDkAAAAADz6bOQAAAAAY4KE5AAAAACpAqDkAAAAAEmCuOQAAAACSQbQ5AAAAAGbmuTkA"
            + "AAAAP1C/OQAAAADEgMQ5AAAAAJN5yTkAAAAARjzOOQAAAABsytI5AAAAAIol1zkAAAAAHE/bOQAAAACYSN85AAAAAGoT4zkAAAAA8rDmOQAA"
            + "AACOIuo5AAAAAIpp7TkAAAAAMofwOQAAAADIfPM5AAAAAIRL9jkAAAAAl/T4OQAAAAAqefs5AAAAAF7a/TkAAAAApgwAOgAAAACFGwE6AAAA"
            + "AFAaAjoAAAAAiQkDOgAAAACu6QM6AAAAADy7BDoAAAAAqH4FOgAAAABpNAY6AAAAAO7cBjoAAAAApngHOgAAAAD6Bwg6AAAAAFaLCDoAAAAA"
            + "GwMJOgAAAACsbwk6AAAAAGrRCToAAAAArSgKOgAAAADVdQo6AAAAADW5CjoAAAAAI/MKOgAAAADyIws6AAAAAPBLCzoAAAAAbWsLOgAAAACz"
            + "ggs6AAAAAAuSCzoAAAAAvJkLOgAAAAANmgs6AAAAAECTCzoAAAAAk4ULOgAAAABIcQs6AAAAAJpWCzoAAAAAxjULOgAAAAAGDws6AAAAAI3i"
            + "CjoAAAAAlrAKOgAAAABUeQo6AAAAAPo8CjoAAAAAufsJOgAAAAC+tQk6AAAAADxrCToAAAAAXRwJOgAAAABNyQg6AAAAADZyCDoAAAAAQRcI"
            + "OgAAAACVuAc6AAAAAFlWBzoAAAAAtPAGOgAAAADHhwY6AAAAALgbBjoAAAAApqwFOgAAAACzOgU6AAAAAP/FBDoAAAAAp04EOgAAAADM1AM6"
            + "AAAAAIhYAzoAAAAA+dkCOgAAAAA6WQI6AAAAAGPWAToAAAAAj1EBOgAAAADWygA6AAAAAE9CADoAAAAAI3D/OQAAAABmWP45AAAAAJM9/TkA"
            + "AAAA1R/8OQAAAABU//o5AAAAADfc+TkAAAAAorb4OQAAAAC8jvc5AAAAAKpk9jkAAAAAiTj1OQAAAAB+CvQ5AAAAAKTa8jkAAAAAHqnxOQAA"
            + "AAAKdvA5AAAAAIJB7zkAAAAApgvuOQAAAACS1Ow5AAAAAF6c6zkAAAAAIGPqOQAAAAD1KOk5AAAAAPLt5zkAAAAALrLmOQAAAAC+deU5AAAA"
            + "ALg45DkAAAAAMvviOQAAAAA+veE5AAAAAPJ+4DkAAAAAXUDfOQAAAACSAd45AAAAAKLC3DkAAAAAoIPbOQAAAACWRNo5AAAAAJkF2TkAAAAA"
            + "s8bXOQAAAAD2h9Y5AAAAAGxJ1TkAAAAAJAvUOQAAAAAtzdI5AAAAAJKP0TkAAAAAX1LQOQAAAACgFc85AAAAAGDZzTkAAAAAqJ3MOQAAAACG"
            + "Yss5AAAAAAEoyjkAAAAAIu7IOQAAAADwtMc5AAAAAHh8xjkAAAAAvkTFOUSsAAAPAAAAAAEAAMybgD4AAAAA879uPgAAAAC0IYk8AAAAAJqH"
            + "nToAAAAAwjCoOQAAAABo7pA6AAAAACxGEDoAAAAAEJC4uAAAAAAwnuM3AAAAAFIhIrYAAAAAOdXAtgAAAABdHRu0AAAAAHMLSLYAAAAAmQMO"
            + "tgAAAAAZzBq2AAAAAARsHbYAAAAAj+kbtgAAAACQkSC2AAAAAOEkHrYAAAAAJSYgtgAAAABvOCG2AAAAANmZJLYAAAAAxpwqtgAAAABd2Sm2"
            + "AAAAAHvNJrYAAAAAFpwltgAAAAClzSK2AAAAAAyzIbYAAAAADbsftgAAAAAquh22AAAAAHpaGrYAAAAAbzYZtgAAAAB3Mxm2AAAAAGTMF7YA"
            + "AAAAi0sTtgAAAAA/khG2AAAAADRUDbYAAAAAHuUHtgAAAADj+QS2AAAAAIB8AbYAAAAA3Lv+tQAAAAAsRAC2AAAAAIvZ/rUAAAAAtSb4tQAA"
            + "AACc+vq1AAAAAEJq/bUAAAAAAnn+tQAAAAByrP61AAAAABli/rUAAAAAqvn9tQAAAAA7t/u1AAAAAGWl9bUAAAAAa4TztQAAAADjUfe1AAAA"
            + "ADR89LUAAAAAkxPxtQAAAACO3PO1AAAAAJ608bUAAAAAtMDttQAAAADda+61AAAAAMme8LUAAAAAMozxtQAAAAAGAu+1AAAAALqc77UAAAAA"
            + "EgzqtQAAAADeZ+m1AAAAAGaY7LUAAAAAkITotQAAAAD2+OW1AAAAAPOe6LUAAAAAQd3mtQAAAACp9+a1AAAAAGOP7LUAAAAAdOzwtQAAAACt"
            + "VvG1AAAAAHkd8bUAAAAAxGPvtQAAAAAV1PC1AAAAAETzgzcAAAAAS6e1OQAAAAA2SA46AAAAAL5RFzgAAAAAUm+ctwAAAAAFLC03AAAAAOxX"
            + "AbcAAAAAuGP0tQAAAACgBD+2AAAAAL3dTLYAAAAAMbU9tgAAAACQDke2AAAAABohRLYAAAAAUddDtgAAAADeVEC2AAAAAC2LQbYAAAAA6/VB"
            + "tgAAAAC2uT+2AAAAADY6QrYAAAAAAOtEtgAAAACEEkS2AAAAAKjBQrYAAAAAHm5BtgAAAAAJEUG2AAAAAArWQbYAAAAA4ulBtgAAAAAt+kC2"
            + "AAAAADNOQLYAAAAAMJpAtgAAAABVRUG2AAAAAJ7BP7YAAAAAk0g+tgAAAADLSz22AAAAAFMMrLYAAAAAB0FOuAAAAACAR5u4AAAAAEtS9LYA"
            + "AAAAHsm0NwAAAAD59Lo5AAAAAC15CDoAAAAAe/64NwAAAAB8iYi3AAAAAGPWDjcAAAAAdNgMtwAAAACtpyq2AAAAAHaqd7YAAAAArf1+tgAA"
            + "AADmAnG2AAAAAFCwd7YAAAAAcyF3tgAAAABbq3m2AAAAAFY3XTcAAAAANjeuOQAAAACK/Ak6AAAAABCnFzgAAAAAlZistwAAAABcbQU3AAAA"
            + "AGfrH7cAAAAAHEyFtgAAAADfr6S2AAAAAIX4q7YAAAAAtEOjtgAAAADV+6W2AAAAAM3SpLYAAAAAW3ultgAAAACGMqS2AAAAAOHUpLYAAAAA"
            + "XXOltgAAAAAL6KS2AAAAANzHp7YAAAAA496ptgAAAACiMKi2AAAAALUfoTcAAAAAkM26OQAAAAAoNQI6AAAAAIqDkLgAAAAAPYjduQAAAACw"
            + "HF65AAAAANI4szcAAAAAu6pwtwAAAACFwbm2AAAAAIargrYAAAAABFfHtgAAAABJ8KW2AAAAALcprbYAAAAAj2SqtgAAAACTaqq2AAAAAJcs"
            + "qLYAAAAAxZGltgAAAABLTai2AAAAAE7lp7YAAAAAuDpBNwAAAAAM8Ks5AAAAABP7CjoAAAAA05sVOAAAAAB/ULm3AAAAACNa8jYAAAAA4jgx"
            + "twAAAABCzKW2AAAAAP+mxLYAAAAAKpnKtgAAAAAue8K2AAAAABRcx7YAAAAAl+jDtgAAAAD0xcK2AAAAALg5wbYAAAAAqda+tgAAAADPML+2"
            + "AAAAAHiibbcAAAAASF89uAAAAAAbCK88AAAAAPyJBTsAAAAAhGkEvAAAAAAybW85AAAAABOnsrsAAAAAlg6KugAAAABUDIO7AAAAAB7FuLoA"
            + "AAAARJ9DuwAAAAAcN8K6AAAAAGA2FrsAAAAAMSS4ugAAAAC28Ou6AAAAAB9LpboAAAAAvAG8ugAAAADpd4+6AAAAAI6Kj7oAAAAA+nApugAA"
            + "AACUt0q6AAAAAGUjULoAAAAAD8VAugAAAAB0NSW6AAAAAKbZGroAAAAA7D8EugAAAAA+EPK5AAAAAIdCz7kAAAAAHDO5uQAAAAAvQp25AAAA"
            + "ABX6iLkAAAAANTJkuQAAAADhBkC5AAAAABfvGbkAAAAAXyz0uAAAAAA+U7S4AAAAAP04ergAAAAAIWp8twAAAAD5cK85AAAAAJHaFToAAAAA"
            + "8s+gOQAAAADRXFg4AAAAAMwfQLkAAAAA2dRwOQAAAABELCQ5AAAAAJDUqzgAAAAA/ecFOQAAAAD+EAU5AAAAAPSMETkAAAAAObQbOQAAAABF"
            + "DSQ5AAAAAAQ7JDkAAAAAuhAMOQAAAAB7tCU5AAAAAOhsRDkAAAAAjryHOQAAAAAhIvc5AAAAAGMEhjkAAAAAmuOQugAAAABfkfi6AAAAAMJi"
            + "1TgAAAAAFlGUOQAAAABUqio5AAAAABEKfDkAAAAAeZxjOQAAAADAGGo5AAAAAE1HbDkAAAAAqIJsOQAAAAAJSG45AAAAAMLsHDkAAAAAgLsA"
            + "AAAAAAAAAQAAtrPkPgAAAAC2s+Q+AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
            + "AAAAACkb3TwAAAAAgMRSOgAAAAC6axq9AAAAAAEqo7wAAAAAkOcvvAAAAAAGYD+7AAAAAIB/oToAAAAAxJWPOwAAAABQmcI7AAAAAHqM4jsA"
            + "AAAABtfrOwAAAAAIg+s7AAAAALjA4TsAAAAAUt3TOwAAAAB0j8I7AAAAAA0LsDsAAAAAyNWcOwAAAABT2Yk7AAAAACS6bjsAAAAAVnlLOwAA"
            + "AACFKSo7AAAAAHQFCzsAAAAA6SvcOgAAAACUvqY6AAAAABpIazoAAAAAO24ROgAAAABYIH45AAAAAAZjLbgAAAAAO0GcuQAAAAACygq6AAAA"
            + "AI1EQboAAAAASv5xugAAAACMsI66AAAAAL7ooboAAAAAWteyugAAAAAmqcG6AAAAADWIzroAAAAA6JvZugAAAAAGCeO6AAAAAOjx6roAAAAA"
            + "hHbxugAAAACitPa6AAAAAPLH+roAAAAALsr9ugAAAAA20/+6AAAAAJt8ALsAAAAAYKgAuwAAAABwdgC7AAAAAEff/7oAAAAATDj+ugAAAAAU"
            + "B/y6AAAAAJxZ+boAAAAA3Tz2ugAAAADJvPK6AAAAAHDk7roAAAAAA77qugAAAADqUua6AAAAAM+r4boAAAAArtDcugAAAADgyNe6AAAAACKb"
            + "0roAAAAAqk3NugAAAAAm5se6AAAAANBpwroAAAAAa928ugAAAABXRbe6AAAAAI+lsboAAAAAtAGsugAAAAATXaa6AAAAAKy6oLoAAAAAOx2b"
            + "ugAAAAAyh5W6AAAAAMz6j7oAAAAACHqKugAAAACzBoW6AAAAAM5Ef7oAAAAAH510ugAAAADlGGq6AAAAAFu6X7oAAAAAdoNVugAAAADodUu6"
            + "AAAAACqTQboAAAAAetw3ugAAAADpUi66AAAAAFb3JLoAAAAAeMobugAAAADZzBK6AAAAAOb+CboAAAAA6mABugAAAAAe5vG5AAAAAMtq4bkA"
            + "AAAAzk/RuQAAAADplMG5AAAAALU5srkAAAAAqj2juQAAAAAaoJS5AAAAAD5ghrkAAAAAaPpwuQAAAAD961W5AAAAAB6TO7kAAAAAhe0huQAA"
            + "AADI+Ai5AAAAAMdk4bgAAAAAeC+yuAAAAAA+TIS4AAAAACFrL7gAAAAAGJextwAAAADIxpW1AAAAAAHxmTcAAAAAYzQcOAAAAABVEWk4AAAA"
            + "AKXNmTgAAAAAGO+9OAAAAAAA8+A4AAAAAKxvATkAAAAACt0ROQAAAACSxCE5AAAAADgpMTkAAAAA6A1AOQAAAACEdU45AAAAAPBiXDkAAAAA"
            + "BNlpOQAAAACK2nY5AAAAACa1gTkAAAAAhMWHOQAAAAC4n405AAAAABRFkzkAAAAA57aYOQAAAAB+9p05AAAAABoFozkAAAAA/OOnOQAAAABX"
            + "lKw5AAAAAGIXsTkAAAAASW61OQAAAAA1mrk5AAAAAEicvTkAAAAAmXXBOQAAAABCJ8U5AAAAAFKyyDkAAAAA0BfMOQAAAADFWM85AAAAAC52"
            + "0jkAAAAABHHVOQAAAAA9Stg5AAAAAMcC2zkAAAAAhpvdOQAAAABkFeA5AAAAADpx4jkAAAAA46/kOQAAAAA00uY5AAAAAPrY6DkAAAAA/sTq"
            + "OQAAAAAIl+w5AAAAANhP7jkAAAAAKPDvOQAAAACvePE5AAAAAB3q8jkAAAAAIkX0OQAAAABoivU5AAAAAJC69jkAAAAAPNb3OQAAAAAL3vg5"
            + "AAAAAJTS+TkAAAAAarT6OQAAAAAghPs5AAAAAEJC/DkAAAAAWu/8OQAAAADvi/05AAAAAIIY/jkAAAAAkpX+OQAAAACcA/85AAAAABZj/zkA"
            + "AAAAdrT/OQAAAAAr+P85AAAAAFQXADoAAAAAKiwAOgAAAADNOgA6AAAAAHFDADoAAAAARkYAOgAAAAB+QwA6AAAAAEY7ADoAAAAAzi0AOgAA"
            + "AABCGwA6AAAAAMsDADoAAAAAKs//OQAAAACOjf85AAAAABJD/zkAAAAABvD+OQAAAACylP45AAAAAGEx/jkAAAAAWcb9OQAAAADeU/05AAAA"
            + "ADXa/DkAAAAAnVn8OQAAAABW0vs5AAAAAKFE+zkAAAAAuLD6OQAAAADUFvo5AAAAAC53+TkAAAAA/NH4OQAAAABzJ/g5AAAAAMp39zkAAAAA"
            + "MMP2OQAAAADUCfY5AAAAAOpL9TkAAAAAnYn0OQAAAAAaw/M5AAAAAI748jkAAAAAICryOQAAAAD8V/E5AAAAAEmC8DkAAAAALqnvOQAAAADS"
            + "zO45AAAAAFTt7TkAAAAA3ArtOQAAAACIJew5AAAAAH496zkAAAAA3FLqOQAAAADCZek5AAAAAEx26DkAAAAAnITnOQAAAADMkOY5AAAAAPaa"
            + "5TkAAAAANqPkOQAAAACmqeM5AAAAAGSu4jkAAAAAhLHhOQAAAAAcs+A5AAAAAEWz3zkAAAAAF7LeOQAAAACmr905AAAAAAis3DkAAAAAUKfb"
            + "OQAAAACTodo5AAAAAOKa2TkAAAAAUZPYOQAAAAD0itc5AAAAANyB1jkAAAAAGXjVOQAAAAC8bdQ5AAAAANJi0zkAAAAAbFfSOQAAAACYS9E5"
            + "AAAAAGY/0DkAAAAA4jLPOQAAAAAcJs45AAAAAB8ZzTk=";

    private static final int MAGIC = 0x4A524556;

    /** 分段 RMS（左右声道合并）。 */
    private static double segmentRms(float[] interleaved, int fromFrame, int toFrame) {
        double sum = 0;
        long count = 0;
        for (int frame = fromFrame; frame < toFrame; frame++) {
            for (int ch = 0; ch < 2; ch++) {
                float v = interleaved[frame * 2 + ch];
                sum += (double) v * v;
                count++;
            }
        }
        return count == 0 ? 0 : Math.sqrt(sum / count);
    }

    @Test
    @DisplayName("与上游参考逐样本吻合：44.1k Default / 44.1k Plate High / 48k Default（各 256 帧）")
    void matchesUpstreamReference() {
        byte[] blob = Base64.getDecoder().decode(REFERENCE_B64);
        ByteBuffer bb = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(MAGIC, bb.getInt(), "参考数据头");
        int count = bb.getInt();
        assertEquals(3, count, "参考数据条目数");

        for (int c = 0; c < count; c++) {
            int rate = bb.getInt();
            int preset = bb.getInt();
            int frames = bb.getInt();

            JdspReverb reverb = new JdspReverb();
            reverb.configure(rate, preset);
            float[] buffer = new float[frames * 2];
            buffer[0] = 1.0f; // 左声道单位脉冲
            reverb.process(buffer, frames);

            float maxDiff = 0;
            int worstIndex = -1;
            for (int i = 0; i < frames * 2; i++) {
                float ref = bb.getFloat();
                float diff = Math.abs(ref - buffer[i]);
                if (diff > maxDiff) {
                    maxDiff = diff;
                    worstIndex = i;
                }
            }
            String tag = "rate=" + rate + " preset=" + preset;
            assertTrue(maxDiff < 1e-3f,
                    tag + "：与参考输出的最大偏差应极小，实测 " + maxDiff
                            + "（第 " + (worstIndex / 2) + " 帧 ch" + (worstIndex % 2) + "）");
        }
    }

    @Test
    @DisplayName("脉冲响应有真实混响尾音：分段 RMS 存在、且呈衰减趋势")
    void impulseProducesAudibleTail() {
        JdspReverb reverb = new JdspReverb();
        reverb.configure(44100, JdspReverb.PRESET_DEFAULT);
        int frames = 16384; // ≈371ms
        float[] buffer = new float[frames * 2];
        buffer[0] = 1.0f;
        reverb.process(buffer, frames);

        double early = segmentRms(buffer, 256, 1024);
        double mid = segmentRms(buffer, 4096, 8192);
        double late = segmentRms(buffer, 12288, 16384);

        assertTrue(early > 1e-4, "早期混响能量应可闻（实测 " + early + "）");
        assertTrue(mid > 1e-7, "中期仍有尾音（实测 " + mid + "）");
        assertTrue(late > 0.0, "晚期仍有残响（实测 " + late + "）");
        assertTrue(early > mid && mid > late,
                "尾音应呈衰减：early=" + early + " mid=" + mid + " late=" + late);
    }

    @Test
    @DisplayName("未 configure 时处理为纯旁路（缓冲原样）")
    void unconfiguredIsBypass() {
        JdspReverb reverb = new JdspReverb();
        float[] buffer = new float[64];
        for (int i = 0; i < 32; i++) {
            buffer[i * 2] = 0.5f;
            buffer[i * 2 + 1] = -0.25f;
        }
        float[] copy = buffer.clone();
        reverb.process(buffer, 32);
        for (int i = 0; i < buffer.length; i++) {
            assertEquals(copy[i], buffer[i], 0.0f, "未配置时必须原样返回");
        }
    }

    @Test
    @DisplayName("不同预设输出显著不同（Default vs Plate High vs Long Reverb）")
    void presetsProduceDifferentOutput() {
        float[] a = impulseResponse(44100, JdspReverb.PRESET_DEFAULT, 2048);
        float[] b = impulseResponse(44100, JdspReverb.PRESET_PLATE_HIGH, 2048);
        float[] c = impulseResponse(44100, JdspReverb.PRESET_LONG_REVERB_1, 2048);

        double diffAb = meanAbsDiff(a, b);
        double diffAc = meanAbsDiff(a, c);
        assertNotEquals(0.0, diffAb, "Default 与 Plate High 不应完全相同");
        assertNotEquals(0.0, diffAc, "Default 与 Long Reverb 不应完全相同");
        assertTrue(diffAb > 1e-6, "Default 与 Plate High 应有可观测差异（实测 " + diffAb + "）");
        assertTrue(diffAc > 1e-6, "Default 与 Long Reverb 应有可观测差异（实测 " + diffAc + "）");
    }

    @Test
    @DisplayName("长时间连续处理无 NaN/Inf（实时安全性）")
    void longRunStaysFinite() {
        JdspReverb reverb = new JdspReverb();
        reverb.configure(48000, JdspReverb.PRESET_LONG_REVERB_2);
        float[] buffer = new float[2048];
        for (int block = 0; block < 24; block++) {
            for (int i = 0; i < 1024; i++) {
                buffer[i * 2] = (float) Math.sin(block * 0.37 + i * 0.011) * 0.4f;
                buffer[i * 2 + 1] = (float) Math.cos(block * 0.29 + i * 0.013) * 0.4f;
            }
            reverb.process(buffer, 1024);
            for (float v : buffer) {
                assertTrue(Float.isFinite(v), "输出必须有限（block " + block + "）");
                assertTrue(Math.abs(v) < 32.0f, "输出不应爆表（实测 " + v + "）");
            }
        }
    }

    @Test
    @DisplayName("重配（换预设/采样率）后状态被重建，不残留旧尾音")
    void reconfigureRebuildsState() {
        JdspReverb reverb = new JdspReverb();
        reverb.configure(44100, JdspReverb.PRESET_DEFAULT);
        float[] warm = new float[8192];
        warm[0] = 1.0f;
        reverb.process(warm, 4096);
        assertTrue(segmentRms(warm, 2048, 4096) > 0.0, "先确认产生了尾音");

        // 重配后（等价于上游重新 Reverb_SetParam）：延迟线全清，首帧只剩干声
        reverb.configure(48000, JdspReverb.PRESET_DEFAULT);
        float[] fresh = new float[256];
        fresh[0] = 1.0f;
        reverb.process(fresh, 128);
        double dryGain = Math.pow(10.0, 0.05 * -7.0);
        assertEquals(dryGain, fresh[0], 0.02, "重配后首帧应≈纯干声（无旧尾音）");
    }

    @Test
    @DisplayName("预设表 19 行、名称齐全、configure 任意索引不炸")
    void presetCatalogIsComplete() {
        assertEquals(19, JdspReverb.PRESET_COUNT);
        assertEquals(19, JdspReverb.PRESET_NAMES.length);
        for (int i = 0; i < JdspReverb.PRESET_NAMES.length; i++) {
            assertTrue(JdspReverb.PRESET_NAMES[i] != null && !JdspReverb.PRESET_NAMES[i].isEmpty(),
                    "预设名不应为空：" + i);
            float[] row = JdspReverb.presetRow(i);
            assertEquals(17, row.length, "预设行应含 17 列（osf + 16 参数）");
        }
        // 越界回退到 Default，不抛异常
        JdspReverb reverb = new JdspReverb();
        reverb.configure(44100, 999);
        assertTrue(reverb.isConfigured());
    }

    // ──────────────── 辅助 ────────────────

    private static float[] impulseResponse(int rate, int preset, int frames) {
        JdspReverb reverb = new JdspReverb();
        reverb.configure(rate, preset);
        float[] buffer = new float[frames * 2];
        buffer[0] = 1.0f;
        reverb.process(buffer, frames);
        return buffer;
    }

    private static double meanAbsDiff(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += Math.abs(a[i] - b[i]);
        }
        return sum / a.length;
    }
}

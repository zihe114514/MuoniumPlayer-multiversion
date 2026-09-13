package com.muoniumplayer.core.ncm.music;

import com.google.gson.Gson;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.settings.ConfigPaths;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/** Persistent profile service for the Folia planner. */
final class FoliaTrackProfiler {
    private static final long WINDOW_MILLIS = 30_000L;
    private static final Gson GSON = new Gson();
    private static final File DIR = new File(ConfigPaths.CACHE_DIR, "automix-profiles");

    private FoliaTrackProfiler() {}

    static FoliaTrackProfile loadOrAnalyse(AudioPlayer player, boolean outgoing) {
        File source = player == null ? null : player.getSourceFile();
        if (source == null || !source.isFile()) return null;
        String key = cacheKey(source);
        FoliaTrackProfile cached = read(key);
        if (cached != null && cached.isUsable()) return cached;
        FoliaTrackProfile profile = analyse(player, source, outgoing);
        if (profile != null) write(key, profile);
        return profile;
    }

    private static FoliaTrackProfile analyse(AudioPlayer player, File source, boolean outgoing) {
        AnalysisAudio audio = AnalysisAudioSource.open(source);
        if (audio == null || audio.sampleRate() <= 0) return null;
        InferenceStatus inference = null;
        try {
            long duration = Math.max(0L, (long) player.getTotalTimeMillis());
            if (duration <= 0L) return null;
            long window = Math.min(WINDOW_MILLIS, duration);
            long start = outgoing ? duration - window : 0L;
            float[] mono = audio.readMono(start, window);
            if (mono.length == 0) return null;
            boolean beatModel = hasBeatModel();
            boolean stemModel = hasStemModel();
            inference = InferenceStatus.of(player, source, beatModel, stemModel);
            FoliaTrackProfile profile = new FoliaTrackProfile();
            profile.cacheKey = cacheKey(source);
            profile.durationMillis = duration;
            profile.tailStartMillis = start;
            AutomixAnalyzer.BeatGrid grid = AutomixAnalyzer.analyzeBeats(mono, audio.sampleRate());
            AutomixAnalyzer.BeatGrid modelGrid = null;
            if (beatModel) {
                inference.begin();
                modelGrid = FoliaBeatThis.analyse(mono, audio.sampleRate(), outgoing);
                if (modelGrid != null) {
                    inference.success();
                    grid = modelGrid;
                } else {
                    inference.failure();
                }
            }
            if (grid != null) {
                if (outgoing) {
                    profile.outroBpm = grid.bpm;
                    profile.outroBeatConfidence = grid.confidence;
                    profile.beatPhaseMillis = grid.phaseMillis;
                    profile.barPhaseMillis = grid.barPhaseMillis;
                } else {
                    profile.headBeatPhaseMillis = grid.phaseMillis;
                    profile.headBarPhaseMillis = grid.barPhaseMillis;
                }
                if (profile.bpm <= 0f) {
                    profile.bpm = grid.bpm;
                    profile.beatConfidence = grid.confidence;
                    profile.beatPhaseMillis = grid.phaseMillis;
                    profile.barPhaseMillis = grid.barPhaseMillis;
                }
            }
            AutomixAnalyzer.KeyEstimate key = AutomixAnalyzer.analyzeKey(mono, audio.sampleRate());
            if (key != null) {
                if (outgoing) { profile.outroKeyTonic=key.tonic; profile.outroKeyMajor=key.major; profile.outroKeyConfidence=key.confidence; }
                else { profile.introKeyTonic=key.tonic; profile.introKeyMajor=key.major; profile.introKeyConfidence=key.confidence; }
            }
            profile.leadInMillis = outgoing ? 0f : AutomixAnalyzer.detectIntroEnd(mono, audio.sampleRate());
            float landingMillis = outgoing ? AutomixAnalyzer.detectOutroLanding(mono, audio.sampleRate()) : 0f;
            // detectOutroLanding 用 -1 表示「整段窗口没有可测电平」，不是「窗口起点」。
            // 直接做 start + landing 会把这个哨兵变成 start-1 这个正数，于是「测不到」
            // 在下游看起来像一个位于窗口开头的合法落点候选。
            profile.outroLandingMillis = outgoing && landingMillis < 0f ? -1f : start + landingMillis;
            profile.headLufs = AutomixAnalyzer.kWeightedLoudness(prefix(mono,audio.sampleRate(),12),audio.sampleRate());
            profile.tailLufs = AutomixAnalyzer.kWeightedLoudness(tail(mono,audio.sampleRate(),12),audio.sampleRate());
            float[] tone = bandShares(mono, audio.sampleRate());
            profile.toneLow = tone[0]; profile.toneMid = tone[1]; profile.toneHigh = tone[2];
            if (stemModel) {
                inference.begin();
                profile.stems = FoliaStemSeparator.analyse(source, start, window, audio, outgoing);
                if (profile.stems != null) inference.success();
                else inference.failure();
            }
            profile.outroSlopeDbPerSecond = outgoing ? slopeDbPerSecond(mono,audio.sampleRate(),10) : 0f;
            profile.startsHot = profile.leadInMillis <= 350f;
            profile.endsHot = !outgoing || endsHot(mono,audio.sampleRate());
            profile.sectionMillis = sectionEdges(mono,audio.sampleRate(),start);
            return profile;
        } catch (Throwable failure) {
            System.err.println("[Automix/Folia] profile failed for "+source.getName()+": "+failure.getMessage());
            return null;
        } finally {
            if (inference != null) inference.finish();
            audio.close();
        }
    }

    private static float[] sectionEdges(float[] mono, int rate, long originMillis) {
        if (mono == null || mono.length == 0 || rate <= 0) return new float[0];
        int step = Math.max(1, rate / 2);
        int cells = Math.max(1, mono.length / step);
        double[] rms = new double[cells];
        for (int c=0;c<cells;c++) rms[c]=AutomixAnalyzer.rms(Arrays.copyOfRange(mono,c*step,Math.min(mono.length,c*step+step)));
        double mean=0.0; for(double v:rms) mean+=v; mean/=Math.max(1,rms.length);
        double variance=0.0; for(double v:rms) variance+=(v-mean)*(v-mean);
        double sigma=Math.sqrt(variance/Math.max(1,rms.length));
        float[] found=new float[cells]; int count=0;
        for(int c=2;c<cells-2;c++){
            double left=(rms[c-2]+rms[c-1])*.5, right=(rms[c]+rms[c+1])*.5;
            if(Math.abs(right-left)>sigma*1.25 && Math.abs(right-left)>mean*.18) found[count++]=originMillis+c*500f;
        }
        return Arrays.copyOf(found,count);
    }

    private static float slopeDbPerSecond(float[] mono, int rate, int seconds) {
        int length=Math.min(mono.length,rate*seconds); if(length<rate) return 0f;
        int half=length/2; int from=mono.length-length;
        float a=AutomixAnalyzer.rms(Arrays.copyOfRange(mono,from,from+half));
        float b=AutomixAnalyzer.rms(Arrays.copyOfRange(mono,from+half,mono.length));
        if(a<=1e-5f||b<=1e-5f) return 0f;
        return (float)(20.0*Math.log10(b/a)/(seconds/2.0));
    }

    private static boolean endsHot(float[] mono, int rate) {
        if(mono==null||mono.length==0||rate<=0) return true;
        int window=Math.min(mono.length,rate*2), previous=Math.min(mono.length-window,rate*6);
        if(previous<=0) return true;
        float a=AutomixAnalyzer.rms(Arrays.copyOfRange(mono,mono.length-window,mono.length));
        float b=AutomixAnalyzer.rms(Arrays.copyOfRange(mono,mono.length-window-previous,mono.length-window));
        return a>.003f && a>=b*.45f;
    }

    private static float[] bandShares(float[] input, int rate) {
        if (input == null || input.length == 0 || rate <= 0) return new float[] {1f/3f, 1f/3f, 1f/3f};
        double a250 = 1.0 - Math.exp(-2.0 * Math.PI * 250.0 / rate);
        double a4k = 1.0 - Math.exp(-2.0 * Math.PI * 4000.0 / rate);
        double lp = 0.0, mp = 0.0, lowEnergy = 0.0, midEnergy = 0.0, highEnergy = 0.0;
        for (float sample : input) {
            lp += a250 * (sample - lp);
            mp += a4k * (sample - mp);
            double low = lp, mid = mp - lp, high = sample - mp;
            lowEnergy += low * low; midEnergy += mid * mid; highEnergy += high * high;
        }
        double total = lowEnergy + midEnergy + highEnergy;
        if (total <= 1e-12) return new float[] {1f/3f, 1f/3f, 1f/3f};
        return new float[] {(float)(lowEnergy/total), (float)(midEnergy/total), (float)(highEnergy/total)};
    }

    private static float[] prefix(float[] input,int rate,int seconds){return Arrays.copyOf(input,Math.min(input.length,rate*seconds));}
    private static float[] tail(float[] input,int rate,int seconds){int len=Math.min(input.length,rate*seconds);return Arrays.copyOfRange(input,input.length-len,input.length);}

    private static FoliaTrackProfile read(String key) {
        try {
            File file=new File(DIR,key+".json"); if(!file.isFile()) return null;
            String json=new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8);
            FoliaTrackProfile profile=GSON.fromJson(json,FoliaTrackProfile.class);
            return profile!=null && profile.version==FoliaTrackProfile.VERSION ? profile : null;
        } catch(Throwable ignored){return null;}
    }

    private static void write(String key, FoliaTrackProfile profile) {
        try {
            if(!DIR.exists()&&!DIR.mkdirs()&&!DIR.isDirectory()) return;
            File target=new File(DIR,key+".json"), part=new File(DIR,key+".json.part");
            Files.write(part.toPath(),GSON.toJson(profile).getBytes(StandardCharsets.UTF_8));
            Files.move(part.toPath(),target.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch(Throwable ignored){}
    }

    private static boolean hasBeatModel() {
        return FoliaModelManager.isInstalled(FoliaModelManager.Kind.BEAT_THIS)
                && FoliaModelManager.isInstalled(FoliaModelManager.Kind.RUNTIME);
    }

    private static boolean hasStemModel() {
        return AutomixSettings.isPerformanceModeEnabled()
                && FoliaModelManager.isInstalled(FoliaModelManager.Kind.HTDEMUCS)
                && FoliaModelManager.isInstalled(FoliaModelManager.Kind.RUNTIME);
    }

    private static final class InferenceStatus {
        private final boolean enabled;
        private final String trackName;
        private final String modelNames;
        private boolean started;
        private int attempts;
        private int successes;
        private long startedAt;

        private InferenceStatus(boolean enabled, String trackName, String modelNames) {
            this.enabled = enabled;
            this.trackName = trackName;
            this.modelNames = modelNames;
        }

        private static InferenceStatus of(AudioPlayer player, File source, boolean beatModel, boolean stemModel) {
            boolean enabled = (beatModel || stemModel) && AutomixSettings.isModelInferenceStatusEnabled();
            String names = beatModel && stemModel ? "Beat This! + HTDemucs"
                    : (beatModel ? "Beat This!" : "HTDemucs");
            String trackName = player == null ? null : player.getTrackName();
            if (trackName == null || trackName.trim().isEmpty()) {
                trackName = source == null ? "当前歌曲" : source.getName();
            }
            return new InferenceStatus(enabled, trackName, names);
        }

        private void begin() {
            if (!enabled || started) return;
            started = true;
            startedAt = System.currentTimeMillis();
            DownloadDynamicIsland.beginModelInference(trackName, modelNames);
        }

        private void success() {
            if (enabled) {
                attempts++;
                successes++;
            }
        }

        private void failure() {
            if (enabled) attempts++;
        }

        private void finish() {
            if (!enabled || !started) return;
            long elapsed = System.currentTimeMillis() - startedAt;
            if (attempts > 0 && successes == attempts) {
                DownloadDynamicIsland.finishModelInference(trackName, modelNames, elapsed);
            } else {
                DownloadDynamicIsland.failModelInference(trackName, modelNames);
            }
        }
    }

    static String cacheKey(File source) {
        String models = (FoliaModelManager.isInstalled(FoliaModelManager.Kind.BEAT_THIS) ? "B" : "-")
                + (AutomixSettings.isPerformanceModeEnabled()
                && FoliaModelManager.isInstalled(FoliaModelManager.Kind.HTDEMUCS)
                && FoliaModelManager.isInstalled(FoliaModelManager.Kind.RUNTIME) ? "S" : "-");
        String value=source.getAbsolutePath()+'|'+source.length()+'|'+source.lastModified()+'|'+models;
        long hash=0xcbf29ce484222325L;
        for(int i=0;i<value.length();i++){hash^=value.charAt(i);hash*=0x100000001b3L;}
        return Long.toUnsignedString(hash,16);
    }
}

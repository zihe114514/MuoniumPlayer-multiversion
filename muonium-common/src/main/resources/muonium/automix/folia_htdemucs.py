#!/usr/bin/env python3
# Clean-room HTDemucs feature runner for MuoniumPlayer Folia mode.
# Input: flat float32 [left(total), right(total)]. Output: JSON transition evidence,
# plus (可选 argv[4]) 窗口长度那么长的人声素材 float32，供交接期按位置减掉人声。
import json
import os
import sys

import numpy as np
import onnxruntime as ort

SOURCES = ["drums", "bass", "other", "vocals"]
RETURNED = ["drums", "bass", "vocals"]
CELL_SECONDS = 0.05


def segment_of(session):
    shape = session.get_inputs()[0].shape
    segment = shape[2]
    if not isinstance(segment, int) or segment <= 0:
        raise RuntimeError("the HTDemucs export must have a static segment length")
    return segment


def build_window(segment):
    overlap = segment // 4
    window = np.ones(segment, dtype=np.float32)
    if overlap > 1:
        ramp = np.arange(overlap, dtype=np.float32) / float(overlap - 1)
        window[:overlap] = ramp
        window[segment - overlap:] = ramp[::-1]
    return window


def make_session(model_path):
    options = ort.SessionOptions()
    # These two flags are the reason this work runs under Python: they keep this graph's peak
    # resident memory far below onnxruntime's defaults.
    options.enable_cpu_mem_arena = False
    options.enable_mem_reuse = False
    options.intra_op_num_threads = 4
    return ort.InferenceSession(model_path, sess_options=options, providers=["CPUExecutionProvider"])


def separate(session, left, right):
    total = int(left.shape[0])
    segment = segment_of(session)
    overlap = segment // 4
    stride = segment - overlap
    chunks = max(1, (total + stride - 1) // stride)
    output = np.zeros((len(RETURNED), 2, total), dtype=np.float32)
    weight = np.zeros(total, dtype=np.float32)
    window = build_window(segment)
    batch = np.zeros((1, 2, segment), dtype=np.float32)
    input_name = session.get_inputs()[0].name
    indices = [SOURCES.index(name) for name in RETURNED]
    for chunk in range(chunks):
        start = chunk * stride
        end = min(start + segment, total)
        if end <= start:
            break
        count = end - start
        batch.fill(0.0)
        batch[0, 0, :count] = left[start:end]
        batch[0, 1, :count] = right[start:end]
        prediction = np.asarray(session.run(None, {input_name: batch})[0])
        output[:, :, start:end] += prediction[0, indices, :, :count] * window[:count]
        weight[start:end] += window[:count]
    np.divide(output, np.maximum(weight, 1e-8)[None, None, :], out=output)
    return {name: (output[index, 0], output[index, 1]) for index, name in enumerate(RETURNED)}


def envelope(values, sample_rate, cell_seconds=CELL_SECONDS):
    hop = max(1, int(round(sample_rate * cell_seconds)))
    frames = values.shape[0] // hop
    if frames <= 0:
        return np.zeros(0, dtype=np.float32)
    frames_view = values[:frames * hop].reshape(frames, hop)
    return np.sqrt(np.mean(np.square(frames_view, dtype=np.float64), axis=1) + 1e-12).astype(np.float32)


def active_threshold(level, mix):
    peak = float(np.percentile(level, 95)) if level.size else 0.0
    mix_peak = float(np.percentile(mix, 90)) if mix.size else 0.0
    return max(1e-5, peak * 0.075, mix_peak * 0.025)


def first_sustained(level, threshold, cells=5):
    run = 0
    for index, value in enumerate(level):
        if value >= threshold:
            run += 1
            if run >= cells:
                return max(0, index - cells + 1)
        else:
            run = 0
    return -1


def last_active(level, threshold):
    indices = np.flatnonzero(level >= threshold)
    return int(indices[-1]) if indices.size else -1


def sustain_end(level, threshold):
    if level.size == 0:
        return -1
    peak = float(np.percentile(level, 95))
    band = max(threshold, peak * 0.30)
    minimum = max(1, int(round(1.2 / CELL_SECONDS)))
    best = -1
    run = 0
    for index, value in enumerate(level):
        if value >= band:
            run += 1
            if run >= minimum:
                best = index
        else:
            run = 0
    return best


def seconds(cell):
    return round(cell * CELL_SECONDS, 4) if cell >= 0 else -1.0


def write_vocal_material(path, vocal):
    """人声素材落盘：交错度=1 的 float32，样本位置与送进来的窗口逐帧对齐。

    交接期要把这个人声从播放素材里减掉（{@code out = raw + vocal * (level - 1)}），所以它必须
    是单声道折下来的那一份 —— 输入本来就是把单声道写到左右两侧，立体声人声在这里没有信息，
    写成两声道只会把缓存翻倍。
    """
    if not path or path == "-":
        return
    np.ascontiguousarray(vocal, dtype="<f4").tofile(path)


def vocal_projection(mix, vocal, env, threshold, hop):
    """人声在混音里的实际比例：把 mix 投影到 vocal 方向上的最小二乘系数。

    分离模型给出的不是「混音里那一份人声」的严格副本，直接相减会留下残留或者把伴奏一起削掉。
    这个系数就是让残差能量最小的那个缩放，交接期用它把素材对回原曲的响度。
    只统计确实有人在唱的时间格 —— 把静音段算进来会把系数拉向 0。

    {@code hop} 由调用方按 envelope() 同一个式子算并传进来：用
    {@code vocal.size // env.size} 反推会在除不尽时逐格累积漂移，把窗口罩到错的样本上。
    """
    if hop <= 0:
        return 1.0
    usable = min(env.shape[0], vocal.shape[0] // hop, mix.shape[0] // hop)
    numerator = 0.0
    denominator = 0.0
    for index in range(usable):
        if env[index] < threshold:
            continue
        start = index * hop
        window = vocal[start:start + hop]
        reference = mix[start:start + hop]
        if window.size != hop or reference.size != hop:
            continue
        numerator += float(np.dot(reference, window))
        denominator += float(np.dot(window, window))
    if denominator <= 1e-12:
        return 1.0
    return float(numerator / denominator)


def main():
    input_path, output_path, model_path = sys.argv[1:4]
    material_path = sys.argv[4] if len(sys.argv) > 4 else "-"
    raw = np.fromfile(input_path, dtype=np.float32)
    if raw.size < 2:
        raise RuntimeError("input is empty")
    total = raw.size // 2
    left = np.ascontiguousarray(raw[:total], dtype=np.float32)
    right = np.ascontiguousarray(raw[total:2 * total], dtype=np.float32)

    session = make_session(model_path)
    stems = separate(session, left, right)
    sample_rate = 44100
    mix = (left + right) * 0.5
    mix_env = envelope(mix, sample_rate)
    vocal = (stems["vocals"][0] + stems["vocals"][1]) * 0.5
    drum = (stems["drums"][0] + stems["drums"][1]) * 0.5
    bass = (stems["bass"][0] + stems["bass"][1]) * 0.5
    vocal_env = envelope(vocal, sample_rate)
    drum_env = envelope(drum, sample_rate)
    bass_env = envelope(bass, sample_rate)

    vocal_threshold = active_threshold(vocal_env, mix_env)
    drum_threshold = active_threshold(drum_env, mix_env)
    bass_threshold = active_threshold(bass_env, mix_env)
    vocal_start = first_sustained(vocal_env, vocal_threshold)
    vocal_end = last_active(vocal_env, vocal_threshold)
    if vocal_end >= 0:
        vocal_end += int(round(0.30 / CELL_SECONDS))  # an under-reported end is the dangerous error
    drum_end = last_active(drum_env, drum_threshold)
    bass_end = last_active(bass_env, bass_threshold)
    release = sustain_end(vocal_env, vocal_threshold)

    mix_energy = float(np.sum(np.square(mix, dtype=np.float64)) + 1e-12)
    stem_energy = float(
        np.sum(np.square(vocal, dtype=np.float64))
        + np.sum(np.square(drum, dtype=np.float64))
        + np.sum(np.square(bass, dtype=np.float64))
    )
    confidence = max(0.0, min(1.0, stem_energy / mix_energy))

    # 人声素材：交接期把这一段按位置减回原曲，让进来的歌先只剩伴奏。
    # 只在调用方给了路径时才写（出场侧只要时间戳，写一份用不上的缓存要 5 MB）。
    write_vocal_material(material_path, vocal)
    projection = vocal_projection(mix, vocal, vocal_env, vocal_threshold,
                                 max(1, int(round(sample_rate * CELL_SECONDS))))

    json.dump({
        "vocal_start": seconds(vocal_start),
        "vocal_end": seconds(vocal_end),
        "drum_end": seconds(drum_end),
        "bass_end": seconds(bass_end),
        "sustained_vocal_end": seconds(release),
        "confidence": round(confidence, 5),
        "vocal_projection": round(projection, 5),
        "sample_rate": sample_rate,
        "samples": total,
    }, open(output_path, "w", encoding="utf-8"), ensure_ascii=False)


if __name__ == "__main__":
    main()